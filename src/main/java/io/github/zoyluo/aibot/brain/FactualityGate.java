package io.github.zoyluo.aibot.brain;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic guard against claiming that unfinished in-world work is complete. */
final class FactualityGate {
    private static final List<String> COMPLETION_TERMS = List.of(
            "已经收集到", "已经采集到", "已经完成", "已经挖掉",
            "已收集到", "已采集到", "已完成", "已挖掉", "任务完成",
            "收集到", "采集到", "挖掉了", "完成了", "拿到",
            "搞定", "砍完", "挖完", "做完", "弄完", "干完",
            "杀完", "打完", "造好", "盖好", "建好", "做好");
    private static final Pattern ENGLISH_COMPLETION =
            Pattern.compile("\\b(done|finished|completed)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_NON_CLAIM_PREFIX = Pattern.compile(
            "(?:还没|尚未|并未|未曾|没有|并没有|没能|未能|不曾|不是|不算|不能算|还差|差点|差一点|"
                    + "正在|还在|准备|打算|快要|马上|等|等到|如果|要是|只要).{0,10}$");
    private static final Pattern ENGLISH_NON_CLAIM_PREFIX = Pattern.compile(
            "(?i)(?:not|never|haven't|hasn't|hadn't|isn't|wasn't|weren't|won't|wouldn't|can't|cannot|"
                    + "will|would|going to|once|when|after|before|if|unless|almost|nearly|still).{0,24}$");
    private static final Set<String> NON_ACTION_TOOLS = Set.of(
            "finish", "speak", "say", "scan_surroundings", "inventory",
            "get_task_status", "goal_status", "world_info", "gear_check",
            "find_block", "find_entity", "find_container", "get_marked_target",
            "list_places", "list_jobs", "recall", "plan_craft", "emote", "roll_dice",
            "remember", "forget", "mark_place", "set_base", "set_goal", "advance_goal",
            "post_job", "tell_bot");

    private FactualityGate() {
    }

    record Context(
            String request,
            boolean fromOwner,
            boolean actionDispatched,
            boolean runningWork,
            boolean activeGoal
    ) {
    }

    record SpeechDecision(String speech, boolean rewritten) {
    }

    record FinishDecision(boolean allowed, String speech, String reason, boolean rewritten) {
        static FinishDecision rejected(String speech) {
            return new FinishDecision(false, speech,
                    "rejected_no_action: 这是行动任务,但本轮还没有成功派发任何行动,也没有任务在运行。"
                            + "先调用实际行动工具;finish 只结束对话轮次,不代表任务完成。",
                    false);
        }
    }

    static FinishDecision reviewFinish(Context context, String summary) {
        boolean physicalCommand = context.fromOwner() && isPhysicalCommand(context.request());
        if (physicalCommand && !context.actionDispatched() && !context.runningWork() && !context.activeGoal()) {
            return FinishDecision.rejected(summary);
        }
        SpeechDecision speech = reviewSpeech(context, summary);
        return new FinishDecision(true, speech.speech(), "", speech.rewritten());
    }

    static SpeechDecision reviewSpeech(Context context, String raw) {
        String speech = raw == null ? "" : raw;
        if (!containsCompletionClaim(speech)) {
            return new SpeechDecision(speech, false);
        }

        boolean physicalCommand = context.fromOwner() && isPhysicalCommand(context.request());
        boolean relevantStatusQuestion = context.fromOwner()
                && isStatusQuestion(context.request())
                && context.runningWork();
        boolean unfinished = context.activeGoal()
                || (context.runningWork()
                && (physicalCommand || context.actionDispatched() || relevantStatusQuestion))
                || (physicalCommand && !context.actionDispatched());
        if (!unfinished) {
            return new SpeechDecision(speech, false);
        }
        return new SpeechDecision(progressSpeech(context.request(), context.runningWork() || context.activeGoal()), true);
    }

    static boolean containsCompletionClaim(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String text = raw.strip();
        if ((text.endsWith("?") || text.endsWith("？")) && !containsAny(text, "。", "！", "!", ";", "；")) {
            return false;
        }
        String[] clauses = text.split("[，,。.!！?？;；\\n\\r]+|但是|不过|可是|然而");
        for (String clause : clauses) {
            String normalized = clause.strip().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            for (String term : COMPLETION_TERMS) {
                int from = 0;
                int index;
                while ((index = normalized.indexOf(term, from)) >= 0) {
                    if (!isChineseNonClaim(normalized, index, term.length())) {
                        return true;
                    }
                    from = index + term.length();
                }
            }
            Matcher english = ENGLISH_COMPLETION.matcher(normalized);
            while (english.find()) {
                if (!isEnglishNonClaim(normalized, english.start(), english.end())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isChineseNonClaim(String clause, int start, int length) {
        String prefix = clause.substring(Math.max(0, start - 24), start);
        String suffix = clause.substring(start + length);
        if (CHINESE_NON_CLAIM_PREFIX.matcher(prefix).find()) {
            return true;
        }
        if (suffix.startsWith("不了") || suffix.startsWith("不成") || suffix.startsWith("吗")
                || suffix.startsWith("了吗") || suffix.startsWith("没有") || suffix.startsWith("没")
                || suffix.startsWith("度")) {
            return true;
        }
        if (suffix.matches("^(?:.{0,8})?(?:后|以后|之前)(?:再|才|就)?.*$")) {
            return true;
        }
        return suffix.matches("^.{0,10}(?:我)?再(?:告诉|说|汇报|回复|通知).*$");
    }

    private static boolean isEnglishNonClaim(String clause, int start, int end) {
        String prefix = clause.substring(Math.max(0, start - 36), start);
        String suffix = clause.substring(end);
        if (ENGLISH_NON_CLAIM_PREFIX.matcher(prefix).find()) {
            return true;
        }
        if (prefix.matches("(?i).*(?:are you|is it|did you|have you|has it).{0,12}$")) {
            return true;
        }
        return suffix.matches("(?i)^.{0,16}(?:later|afterwards|then I(?:'ll| will) (?:tell|report)).*$");
    }

    static boolean isPhysicalCommand(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        if (isStatusQuestion(text) || isInformationalQuestion(text) || isStopRequest(text)) {
            return false;
        }
        if (containsAny(text, "打招呼", "打个招呼", "给我讲", "给我说", "给我解释", "给我回答", "给我看看")) {
            return false;
        }
        return containsAny(text,
                "挖", "砍", "采集", "收集", "合成", "制作", "熔炼", "烧炼",
                "建造", "盖", "击杀", "杀", "攻击", "打死", "打掉", "打怪", "打我",
                "种植", "种树", "播种", "收割", "钓鱼", "给我", "拿给", "递给",
                "拿来", "带来", "放置", "放下", "扔", "吃掉", "吃点", "睡觉",
                "跟随", "跟着", "过来", "回来", "去找", "去拿", "去挖", "去砍",
                "去采", "去收", "去建", "去盖", "去杀", "放烟花");
    }

    static boolean isStatusQuestion(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        return containsAny(text,
                "进度", "状态", "到哪了", "到哪儿了", "还要多久", "做完没", "做完了吗",
                "完成没", "完成了吗", "砍完没", "砍完了吗", "挖完没", "挖完了吗",
                "拿到没", "拿到了吗", "收集完没", "好了没", "成功没", "失败了吗");
    }

    static boolean isActionTool(String toolName) {
        return toolName != null && !toolName.isBlank() && !NON_ACTION_TOOLS.contains(toolName);
    }

    private static boolean isStopRequest(String text) {
        if (containsAny(text, "改去", "改成", "改为", "然后去", "但是去")) {
            return false;
        }
        return containsAny(text,
                "停止", "停下", "取消任务", "算了", "放弃", "别做了", "不用做了",
                "别挖", "别砍", "别采", "别收集", "别打", "别杀", "别跟", "不要挖",
                "不要砍", "不要采", "不要收集", "不要打", "不要杀", "不要跟");
    }

    static boolean isInformationalQuestion(String text) {
        boolean asksForInformation = containsAny(text,
                "怎么", "如何", "为什么", "什么", "多少", "哪里", "哪儿", "在哪",
                "需要", "应该", "会不会", "知不知道", "有没有", "是什么", "什么意思");
        if (!asksForInformation) {
            return false;
        }
        return !containsAny(text, "帮我", "替我", "给我", "麻烦你", "请你");
    }

    private static String progressSpeech(String rawRequest, boolean running) {
        if (!running) {
            return "我还没动手，先干活。";
        }
        String request = rawRequest == null ? "" : rawRequest.toLowerCase(Locale.ROOT);
        if (containsAny(request, "收集", "采集", "砍", "树", "木")) {
            return "还在收集，拿到手我再告诉你。";
        }
        if (containsAny(request, "挖", "矿")) {
            return "还在挖，真挖到了我再说。";
        }
        if (containsAny(request, "合成", "制作", "熔炼", "烧")) {
            return "还在做，成品出来我再告诉你。";
        }
        if (containsAny(request, "建", "盖", "房", "墙")) {
            return "还在盖，弄好我再说。";
        }
        if (containsAny(request, "杀", "打", "攻击")) {
            return "还在打，结束了我再说。";
        }
        if (containsAny(request, "去", "走", "跟", "过来", "回来", "到")) {
            return "还在路上，到了我再说。";
        }
        if (containsAny(request, "给我", "拿给", "递给", "带来")) {
            return "还在准备，送到手我再说。";
        }
        return "还在弄，真做完了我再说。";
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
