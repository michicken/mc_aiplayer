package io.github.zoyluo.aibot.brain;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds read-only Step lanes around one immutable conversation frame. The main brain remains the
 * sole tool writer; these lanes only contribute bounded notes to its next request.
 */
final class ParallelBrainAdvisors {
    static final int ADVISOR_MAX_TOKENS = 768;
    static final int ADVISOR_TIMEOUT_SECONDS = 8;

    private static final List<Lane> LANES = List.of(
            new Lane("strategy", """
                    你是只读行动策划。根据共享对话、记忆和 Current state，找出主人这条指令真正要达成的结果。
                    只输出：下一步最高层行动、必要前置、不要做的多余动作。不要调用工具，不要寒暄，不要声称完成。
                    """),
            new Lane("world", """
                    你是只读世界可行性顾问。检查当前地形、水、敌怪、距离、背包和任务状态会怎样影响执行。
                    只输出最重要的路径/生存风险和一个可行替代方案。不要调用工具，不要口播，不要编造看不见的方块。
                    """),
            new Lane("memory", """
                    你是只读连续性顾问。检查主人完整要求、已有任务、长期目标和共享记忆，指出尚未满足的部分或不该中断的工作。
                    只输出不超过三条具体约束。不要调用工具，不要把旧任务当成新指令已经完成。
                    """),
            new Lane("critic", """
                    你是只读事实与冲突审查。找出这一步最容易出现的假完成、工具冲突、危险动作或任务覆盖问题。
                    只输出可验证的拒绝条件和必须保留的事实。不要调用工具，不要写客套话。
                    """),
            new Lane("voice", """
                    你是直播口播编辑。只根据共享状态，为“刚开始执行”的时刻给一句自然中文短话，最多 30 个汉字。
                    不得声称已经完成，不称呼主人，不说主播/节目效果/拉满/收到/我将。没有必要说话时只输出 [SILENT]。
                    除这一句外不要输出任何内容，也不要调用工具。
                    """));

    private ParallelBrainAdvisors() {
    }

    static List<LaneRequest> requests(List<ChatMessage> sharedHistory, long frameId) {
        List<ChatMessage> readOnlyHistory = readOnlyHistory(sharedHistory);
        List<LaneRequest> requests = new ArrayList<>(LANES.size());
        for (Lane lane : LANES) {
            List<ChatMessage> history = new ArrayList<>(readOnlyHistory);
            history.add(ChatMessage.system("[parallel_lane=" + lane.id() + " frame=" + frameId + "]\n" + lane.instruction()));
            requests.add(new LaneRequest(lane.id(), List.copyOf(history)));
        }
        return requests;
    }

    /** Converts prior tool protocol records into ordinary evidence for calls that expose no tools. */
    private static List<ChatMessage> readOnlyHistory(List<ChatMessage> sharedHistory) {
        List<ChatMessage> sanitized = new ArrayList<>(sharedHistory.size());
        for (ChatMessage message : sharedHistory) {
            if ("tool".equals(message.role())) {
                sanitized.add(ChatMessage.system("[previous_tool_result] " + clean(message.content(), 500)));
            } else if ("assistant".equals(message.role()) && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                sanitized.add(ChatMessage.assistant(message.content(), List.of()));
            } else {
                sanitized.add(message);
            }
        }
        return sanitized;
    }

    static String mergeDigest(long frameId, List<LaneResult> results) {
        StringBuilder out = new StringBuilder("[parallel_advisors frame=").append(frameId).append("]\n")
                .append("以下是同一世界快照上的只读建议。你仍是唯一能调用工具的主执行者；只采纳能被当前状态支持的建议，不要复述这些建议。\n");
        for (LaneResult result : results) {
            if ("voice".equals(result.lane()) || result.content() == null || result.content().isBlank()) {
                continue;
            }
            out.append(laneLabel(result.lane())).append("：").append(clean(result.content(), 360)).append('\n');
        }
        return out.toString();
    }

    static String voiceSuggestion(List<LaneResult> results) {
        for (LaneResult result : results) {
            if (!"voice".equals(result.lane())) {
                continue;
            }
            String text = clean(result.content(), 80);
            return text.contains("[SILENT]") ? "" : text;
        }
        return "";
    }

    private static String laneLabel(String lane) {
        return switch (lane) {
            case "strategy" -> "策划";
            case "world" -> "世界";
            case "memory" -> "连续性";
            case "critic" -> "审查";
            default -> lane;
        };
    }

    private static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    record LaneRequest(String lane, List<ChatMessage> history) {
    }

    record LaneResult(String lane, String content, String error, int promptTokens, int completionTokens) {
        static LaneResult failure(String lane, Throwable error) {
            String message = error == null || error.getMessage() == null ? "advisor_error" : error.getMessage();
            return new LaneResult(lane, "", message, 0, 0);
        }

        static LaneResult timeout(String lane) {
            return new LaneResult(lane, "", "advisor_timeout", 0, 0);
        }
    }

    private record Lane(String id, String instruction) {
    }
}
