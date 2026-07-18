package io.github.zoyluo.aibot.brain;

import java.util.EnumSet;
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
            "杀完", "打完", "造好", "盖好", "建好", "做好",
            "到手", "备齐", "凑齐", "齐活", "完工", "收工",
            "弄好了", "整好了", "搞好了", "处理好了", "解决了", "成功了");
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
            "list_places", "list_jobs", "recall", "plan_craft",
            "remember", "forget", "mark_place", "set_base", "set_goal", "advance_goal",
            "post_job", "tell_bot");
    private static final Set<String> SAFE_AFTER_TASK_START = Set.of(
            "finish", "speak", "say", "scan_surroundings", "inventory",
            "get_task_status", "goal_status", "world_info", "gear_check",
            "find_block", "find_entity", "find_container", "get_marked_target",
            "list_places", "list_jobs", "recall", "plan_craft");

    private FactualityGate() {
    }

    record Context(
            String request,
            boolean fromOwner,
            boolean actionDispatched,
            boolean runningWork,
            boolean activeGoal,
            boolean taskDispatched,
            boolean taskCompleted,
            boolean taskFailed
    ) {
        Context(String request,
                boolean fromOwner,
                boolean actionDispatched,
                boolean runningWork,
                boolean activeGoal) {
            this(request, fromOwner, actionDispatched, runningWork, activeGoal, false, false, false);
        }
    }

    record SpeechDecision(String speech, boolean rewritten) {
    }

    record FinishDecision(boolean allowed, String speech, String reason, boolean rewritten) {
        static FinishDecision rejected(String speech) {
            return new FinishDecision(false, speech,
                    "rejected_no_action: 当前要求还没有启动能真正完成它的行动。"
                            + "已有的无关动作或旧任务不能算证据;先调用相符的执行工具。",
                    false);
        }

        static FinishDecision rejectedIncomplete(String speech) {
            return new FinishDecision(false, speech,
                    "rejected_incomplete: 前一步已经结束,但主人完整要求还有未执行部分。"
                            + "现在调用能完成剩余要求的行动工具;不要只口头承诺或把中间步骤当完成。",
                    false);
        }
    }

    static FinishDecision reviewFinish(Context context, String summary) {
        boolean actionRequest = context.fromOwner() && requiresActionDispatch(context.request());
        // A task already running from an earlier turn is not evidence that this new request was
        // accepted. The current turn must either dispatch work or explicitly ask for missing
        // information; otherwise a bare finish turns an oral promise into a silent no-op.
        if (actionRequest && !context.actionDispatched() && !isClarificationRequest(summary)) {
            return FinishDecision.rejected(summary);
        }
        if (actionRequest && context.actionDispatched() && !context.taskCompleted()
                && !context.runningWork() && !context.activeGoal() && !context.taskFailed()
                && !isClarificationRequest(summary)) {
            return FinishDecision.rejectedIncomplete(summary);
        }
        SpeechDecision speech = reviewSpeech(context, summary);
        return new FinishDecision(true, speech.speech(), "", speech.rewritten());
    }

    static SpeechDecision reviewSpeech(Context context, String raw) {
        String speech = raw == null ? "" : raw;
        if (!containsCompletionClaim(speech)) {
            return new SpeechDecision(speech, false);
        }

        boolean physicalCommand = context.fromOwner() && requiresActionDispatch(context.request());
        boolean relevantStatusQuestion = context.fromOwner()
                && isStatusQuestion(context.request())
                && context.runningWork();
        boolean completionConfirmed = context.actionDispatched()
                && context.taskCompleted()
                && !context.activeGoal();
        boolean unfinished = context.activeGoal()
                || (context.taskDispatched() && !completionConfirmed)
                || (context.runningWork()
                && (physicalCommand || context.actionDispatched() || relevantStatusQuestion))
                || (physicalCommand && !context.actionDispatched())
                || (physicalCommand && context.taskFailed());
        if (!unfinished) {
            return new SpeechDecision(speech, false);
        }
        if (physicalCommand && context.taskFailed()) {
            return new SpeechDecision("没做成，刚才那步失败了。", true);
        }
        return new SpeechDecision(progressSpeech(context.request(),
                context.runningWork() || context.activeGoal() || context.taskDispatched()), true);
    }

    /** A verbal promise is not an execution result and must never become the only visible outcome. */
    static boolean isUnbackedActionCommitment(Context context, String raw) {
        if (context == null || !context.fromOwner() || !requiresActionDispatch(context.request())
                || isClarificationRequest(raw)) {
            return false;
        }
        boolean backed = context.actionDispatched()
                && (context.runningWork() || context.activeGoal() || context.taskCompleted());
        if (backed) {
            return false;
        }
        String text = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return containsAny(text,
                "我去", "我会", "我来", "我先", "我马上", "我立刻", "我现在", "我开始",
                "马上去", "立刻去", "现在去", "开始干", "开始做", "出发", "动手");
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
                "挖", "砍", "采集", "收集", "捡", "钓", "找吃", "打猎", "挤奶", "剪羊毛",
                "舀水", "舀岩浆", "合成", "制作", "熔炼", "烧炼", "烧矿",
                "建造", "盖", "造房", "修路", "铺路", "搭桥", "砌墙", "推平", "照亮",
                "击杀", "杀", "攻击", "打死", "打掉", "打怪", "打我", "追杀", "护卫", "保护我", "守着",
                "种植", "种树", "播种", "收割", "耕地", "浇水", "催熟", "繁殖",
                "给我", "拿给", "递给", "交给", "拿来", "带来", "存进", "取出来", "穿上", "装备上",
                "拿在手", "丢掉", "整理背包", "放置", "放下", "扔", "吃掉", "吃点", "睡觉", "喝掉",
                "开门", "关门", "拉杆", "按钮", "骑上", "下船", "下马", "驯服", "喂", "敲钟", "使用",
                "看着", "转向", "跳舞", "挥手", "点头", "摇头", "转圈", "鞠躬", "庆祝", "放烟花", "掷骰子",
                "跟随", "跟着", "过来", "回来", "移动", "走到", "探索", "巡逻", "逃跑", "快逃",
                "上去", "下去", "回地面", "去找", "去拿", "去挖", "去砍",
                "去采", "去收", "去建", "去盖", "去杀");
    }

    /** Whether this owner message needs a real action result in the current turn. */
    static boolean requiresActionDispatch(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        if (isStatusQuestion(text) || isInformationalQuestion(text) || isContinuationRequest(text)) {
            return false;
        }
        if (isStopRequest(text) || isPhysicalCommand(text)) {
            return true;
        }
        // Step often receives a natural assignment without a concrete Minecraft verb, for example
        // "给你分配个任务，处理一下". Treat those as work requests too, so it cannot just promise.
        return containsAny(text,
                "分配任务", "给你任务", "这个任务", "帮我处理", "请你处理", "麻烦处理",
                "替我处理", "执行一下", "执行这个", "去做", "做一下", "弄一下", "搞一下", "开始干");
    }

    static boolean isClarificationRequest(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        return text.endsWith("?") || text.endsWith("？")
                || containsAny(text,
                "请标记", "标一下", "给个坐标", "告诉我位置", "具体位置", "哪个位置",
                "哪一棵", "哪一个", "不清楚", "看不清", "需要什么材料", "请说明");
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

    private static boolean isContinuationRequest(String text) {
        return text.startsWith("继续") || text.startsWith("接着") || text.startsWith("接着干")
                || text.startsWith("continue") || text.startsWith("resume");
    }

    static boolean isActionTool(String toolName) {
        return toolName != null && !toolName.isBlank() && !NON_ACTION_TOOLS.contains(toolName);
    }

    static boolean isSafeAfterTaskStart(String toolName) {
        return toolName != null && SAFE_AFTER_TASK_START.contains(toolName);
    }

    enum ActionCapability {
        NAVIGATION,
        RESOURCE,
        CRAFT,
        BUILD,
        COMBAT,
        FARM,
        INVENTORY,
        INTERACTION,
        PERFORMANCE,
        STOP,
        GENERIC
    }

    /**
     * Converts the owner's wording into independently verifiable parts. A preparatory move can
     * satisfy NAVIGATION, but it cannot satisfy RESOURCE for "go to the marker and chop a tree".
     */
    static Set<ActionCapability> requiredCapabilities(String raw) {
        EnumSet<ActionCapability> required = EnumSet.noneOf(ActionCapability.class);
        if (!requiresActionDispatch(raw)) {
            return required;
        }
        String text = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (isStopRequest(text)) {
            required.add(ActionCapability.STOP);
            return required;
        }
        if (containsAny(text,
                "挖", "砍", "采集", "收集", "捡", "钓", "打猎", "找吃", "找木", "找石",
                "挤奶", "剪羊毛", "舀水", "舀岩浆", "回收掉落", "木头", "原木", "树", "石头",
                "圆石", "矿石", "煤炭", "钻石", "沙子", "泥土", "种子", "羊毛", "牛奶", "食物")) {
            required.add(ActionCapability.RESOURCE);
        }
        if (containsAny(text,
                "合成", "制作", "熔炼", "烧炼", "烧矿", "做一把", "做一件", "做个",
                "成品", "铁锭", "金锭", "装备", "工具", "盔甲", "镐", "斧", "锹", "剑",
                "弓", "盾", "剪刀", "工作台", "熔炉", "箱子", "床", "船", "头盔", "胸甲", "护腿", "靴子")) {
            required.add(ActionCapability.CRAFT);
        }
        if (containsAny(text,
                "建造", "盖", "造房", "修路", "铺路", "搭桥", "砌墙", "墙", "房子",
                "放置", "摆下", "推平", "照亮", "掩体", "工作站")) {
            required.add(ActionCapability.BUILD);
        }
        if (containsAny(text,
                "击杀", "追杀", "攻击", "打死", "打怪", "打我", "杀掉", "护卫", "保护我",
                "守着", "射箭", "用弓")) {
            required.add(ActionCapability.COMBAT);
        }
        if (containsAny(text,
                "种植", "种树", "播种", "收割", "耕地", "农田", "浇水", "催熟", "繁殖")) {
            required.add(ActionCapability.FARM);
        }
        if (containsAny(text,
                "递给", "交给", "扔给", "拿给我", "带给我", "拿来", "带来", "存进", "放进箱", "取出来",
                "穿上", "装备上", "拿在手", "副手", "丢掉", "整理背包")) {
            required.add(ActionCapability.INVENTORY);
        }
        if (containsAny(text,
                "开门", "关门", "拉杆", "按钮", "睡觉", "吃掉", "吃点", "喝掉", "骑上",
                "下船", "下马", "驯服", "喂", "敲钟", "使用", "看着", "转向")) {
            required.add(ActionCapability.INTERACTION);
        }
        if (containsAny(text,
                "跳舞", "挥手", "点头", "摇头", "转圈", "鞠躬", "庆祝", "放烟花", "掷骰子",
                "扔骰子", "蹲下", "表演")) {
            required.add(ActionCapability.PERFORMANCE);
        }
        if (containsAny(text,
                "过去", "过来", "回来", "跟着", "跟随", "移动", "走到", "到标记", "去标记",
                "标记处", "标记的", "那里", "那边", "那个位置", "对面",
                "探索", "巡逻", "逃跑", "快逃", "上去", "下去", "回地面", "去找", "去拿",
                "去挖", "去砍", "去采", "去收", "去建", "去盖", "去杀", "带来", "拿来")) {
            required.add(ActionCapability.NAVIGATION);
        }
        if (required.isEmpty()) {
            required.add(ActionCapability.GENERIC);
        }
        return required;
    }

    /** Capabilities proven by a successful tool result; durable tools are only settled later. */
    static Set<ActionCapability> capabilitiesForTool(String toolName) {
        if (toolName == null || toolName.isBlank() || !isActionTool(toolName)) {
            return EnumSet.noneOf(ActionCapability.class);
        }
        return switch (toolName) {
            case "smart_navigate", "move_to", "come_here", "pillar_up", "go_surface",
                    "descend_to_y", "explore", "wander", "flee", "scaffold_walk", "patrol",
                    "unstuck", "make_path", "follow", "goto_place" ->
                    EnumSet.of(ActionCapability.NAVIGATION);
            case "gather", "mine_block", "mine_vein", "mine_ore", "strip_mine", "forage",
                    "fish", "collect_lava", "milk_cow", "shear_sheep", "pickup_items",
                    "recover_drops", "resume_mining", "mine_and_stockpile" ->
                    EnumSet.of(ActionCapability.RESOURCE, ActionCapability.NAVIGATION);
            case "craft", "smelt", "achieve_armor", "achieve_workstation" ->
                    EnumSet.of(ActionCapability.CRAFT);
            case "achieve_goal", "provision_food" ->
                    EnumSet.of(ActionCapability.CRAFT, ActionCapability.RESOURCE,
                            ActionCapability.NAVIGATION);
            case "harvest_crop", "farm", "harvest", "raid_crops", "plant_sapling",
                    "bone_meal", "irrigate", "breed" ->
                    EnumSet.of(ActionCapability.FARM, ActionCapability.RESOURCE,
                            ActionCapability.NAVIGATION, ActionCapability.INTERACTION);
            case "place_block", "build_house", "build_wall", "build_golem", "flatten_area",
                    "place_boat", "light_area", "shelter_now", "create_obsidian" ->
                    EnumSet.of(ActionCapability.BUILD, ActionCapability.NAVIGATION);
            case "smart_combat", "attack", "chase_attack", "guard", "attack_entity",
                    "shoot_bow" ->
                    EnumSet.of(ActionCapability.COMBAT, ActionCapability.NAVIGATION);
            case "select_hotbar", "equip_best_tool", "equip_armor", "drop_item", "give_item",
                    "deposit_all", "deposit", "withdraw", "hold_item", "compact_inventory",
                    "drop_junk", "swap_hands" ->
                    EnumSet.of(ActionCapability.INVENTORY);
            case "look_at", "face", "eat", "sleep", "trade", "ride", "dismount", "tame",
                    "use_bucket", "toggle_door", "use_item", "use_held_item", "pet_command",
                    "feed_pet", "ring_bell", "extinguish_fire", "hold" ->
                    EnumSet.of(ActionCapability.INTERACTION);
            case "emote", "firework", "roll_dice", "sneak", "sprint", "throw_at" ->
                    EnumSet.of(ActionCapability.PERFORMANCE);
            case "stop", "abort_task" -> EnumSet.of(ActionCapability.STOP);
            case "assign_task", "run_command" -> EnumSet.allOf(ActionCapability.class);
            default -> EnumSet.of(ActionCapability.GENERIC);
        };
    }

    static Set<ActionCapability> relevantCapabilities(String request, String toolName) {
        Set<ActionCapability> required = requiredCapabilities(request);
        Set<ActionCapability> offered = capabilitiesForTool(toolName);
        EnumSet<ActionCapability> relevant = EnumSet.noneOf(ActionCapability.class);
        if (required.contains(ActionCapability.GENERIC) && isActionTool(toolName)) {
            relevant.add(ActionCapability.GENERIC);
            return relevant;
        }
        for (ActionCapability capability : offered) {
            if (required.contains(capability)) {
                relevant.add(capability);
            }
        }
        return relevant;
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
            return "还没开工，不能算做完。";
        }
        String request = rawRequest == null ? "" : rawRequest.toLowerCase(Locale.ROOT);
        if (containsAny(request, "收集", "采集", "砍", "树", "木")) {
            return "还没收齐，我接着找。";
        }
        if (containsAny(request, "挖", "矿")) {
            return "还没挖够，我接着挖。";
        }
        if (containsAny(request, "合成", "制作", "熔炼", "烧")) {
            return "成品还没出来，正弄着。";
        }
        if (containsAny(request, "建", "盖", "房", "墙")) {
            return "还没完工，正搭着呢。";
        }
        if (containsAny(request, "杀", "打", "攻击")) {
            return "还没打完，目标还在。";
        }
        if (containsAny(request, "去", "走", "跟", "过来", "回来", "到")) {
            return "还在路上，没到呢。";
        }
        if (containsAny(request, "给我", "拿给", "递给", "带来")) {
            return "东西还没到手，正弄着。";
        }
        return "还没做完，我接着干。";
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
