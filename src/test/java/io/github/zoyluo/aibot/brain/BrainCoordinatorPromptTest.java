package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainCoordinatorPromptTest {
    @Test
    void withholdsFinishUntilPhysicalWorkHasStartedOrNeedsAQuestion() {
        assertTrue(BrainCoordinator.withholdFinishAtActionStart(true, "把这头驴杀掉", false, false));
        assertFalse(BrainCoordinator.withholdFinishAtActionStart(true, "把这头驴杀掉", true, false));
        assertFalse(BrainCoordinator.withholdFinishAtActionStart(true, "把这头驴杀掉", false, true));
        assertFalse(BrainCoordinator.withholdFinishAtActionStart(true, "附近有什么", false, false));
    }

    @Test
    void promptDefinesFinishAsPostSettlementOnly() {
        String prompt = BrainCoordinator.systemPrompt("DeepSeek");

        assertTrue(prompt.contains("finish 不是“收到”"));
        assertTrue(prompt.contains("行动工具返回 assigned 或任务状态 RUNNING 后，停止调用工具且绝不 finish"));
        assertTrue(prompt.contains("COMPLETED 或 FAILED 后才能调用一次 finish"));
    }
}
