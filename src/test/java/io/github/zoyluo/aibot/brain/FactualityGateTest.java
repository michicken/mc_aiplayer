package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactualityGateTest {
    @Test
    void detectsScreenshotStyleAndEnglishCompletionClaims() {
        assertTrue(FactualityGate.containsCompletionClaim("已挖掉标记处的树，拿到木头。"));
        assertTrue(FactualityGate.containsCompletionClaim("已经完成了，搞定。"));
        assertTrue(FactualityGate.containsCompletionClaim("木头收集到了。"));
        assertTrue(FactualityGate.containsCompletionClaim("Task finished."));
        assertTrue(FactualityGate.containsCompletionClaim("Done"));
    }

    @Test
    void ignoresNegativeOngoingFutureAndQuestionPhrases() {
        assertFalse(FactualityGate.containsCompletionClaim("还没完成。"));
        assertFalse(FactualityGate.containsCompletionClaim("正在砍。"));
        assertFalse(FactualityGate.containsCompletionClaim("还在收集。"));
        assertFalse(FactualityGate.containsCompletionClaim("拿到后再说。"));
        assertFalse(FactualityGate.containsCompletionClaim("做完我再告诉你。"));
        assertFalse(FactualityGate.containsCompletionClaim("砍完这棵树以后再汇报。"));
        assertFalse(FactualityGate.containsCompletionClaim("还在收集，拿到手我再告诉你。"));
        assertFalse(FactualityGate.containsCompletionClaim("Not finished yet."));
        assertFalse(FactualityGate.containsCompletionClaim("完成了吗？"));
    }

    @Test
    void rejectsFinishWhenOwnerPhysicalCommandHasNoAction() {
        FactualityGate.Context context = new FactualityGate.Context(
                "去标记处砍一棵树", true, false, false, false);

        FactualityGate.FinishDecision decision = FactualityGate.reviewFinish(
                context, "我去砍树。"
        );

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("rejected_no_action"));
    }

    @Test
    void rejectsBareFinishEvenWhenEarlierWorkIsStillRunning() {
        FactualityGate.Context context = new FactualityGate.Context(
                "去标记处砍一棵树", true, false, true, true);

        FactualityGate.FinishDecision decision = FactualityGate.reviewFinish(
                context, "我去砍树。"
        );

        assertFalse(decision.allowed());
    }

    @Test
    void rewritesActiveGatherCompletionToVerifiedProgress() {
        FactualityGate.Context context = new FactualityGate.Context(
                "去标记处收集一块橡木", true, true, true, false);

        FactualityGate.FinishDecision decision = FactualityGate.reviewFinish(
                context, "已挖掉标记处的树，拿到木头。"
        );

        assertTrue(decision.allowed());
        assertTrue(decision.rewritten());
        assertEquals("还在收集，拿到手我再告诉你。", decision.speech());
    }

    @Test
    void taskAssignmentIsNotCompletionEvidence() {
        FactualityGate.Context context = new FactualityGate.Context(
                "去标记处砍一棵树", true, true, false, false,
                true, false, false);

        FactualityGate.FinishDecision decision = FactualityGate.reviewFinish(
                context, "已经挖掉标记处的树，拿到木头。"
        );

        assertTrue(decision.allowed());
        assertTrue(decision.rewritten());
        assertEquals("还在收集，拿到手我再告诉你。", decision.speech());
    }

    @Test
    void taskCompletionCanOnlyBeClaimedAfterTheTaskManagerConfirmsIt() {
        FactualityGate.Context context = new FactualityGate.Context(
                "去标记处砍一棵树", true, true, false, false,
                true, true, false);

        FactualityGate.FinishDecision decision = FactualityGate.reviewFinish(
                context, "已经挖掉标记处的树，拿到木头。"
        );

        assertFalse(decision.rewritten());
    }

    @Test
    void leavesUnrelatedCasualAnswerAloneDuringBackgroundTask() {
        FactualityGate.Context context = new FactualityGate.Context(
                "这个问题回答得挺搞定的吧", true, false, true, false);

        FactualityGate.SpeechDecision decision = FactualityGate.reviewSpeech(
                context, "这题已经完成了，答案是四十二。"
        );

        assertFalse(decision.rewritten());
    }

    @Test
    void activeGoalAlwaysBlocksPrematureCompletionClaim() {
        FactualityGate.Context context = new FactualityGate.Context(
                "现在怎么样", true, false, false, true);

        FactualityGate.SpeechDecision decision = FactualityGate.reviewSpeech(
                context, "已经完成。"
        );

        assertTrue(decision.rewritten());
        assertEquals("还在弄，真做完了我再说。", decision.speech());
    }

    @Test
    void classifiesCommandsAndReadOnlyToolsForVerbalOnlyGuard() {
        assertTrue(FactualityGate.isPhysicalCommand("给我收集一块橡木"));
        assertTrue(FactualityGate.isPhysicalCommand("去挖三块铁矿"));
        assertFalse(FactualityGate.isPhysicalCommand("挖完了吗？"));
        assertFalse(FactualityGate.isPhysicalCommand("别挖了"));
        assertFalse(FactualityGate.isPhysicalCommand("给我讲个笑话"));
        assertFalse(FactualityGate.isPhysicalCommand("钻石应该怎么挖？"));
        assertTrue(FactualityGate.isPhysicalCommand("帮我挖三块钻石可以吗？"));
        assertTrue(FactualityGate.requiresActionDispatch("给你分配任务，处理一下附近的树"));
        assertTrue(FactualityGate.requiresActionDispatch("去标记处砍一棵树"));
        assertFalse(FactualityGate.requiresActionDispatch("继续"));
        assertFalse(FactualityGate.requiresActionDispatch("任务完成了吗？"));
        assertTrue(FactualityGate.isStatusQuestion("挖完了吗？"));
        assertTrue(FactualityGate.isInformationalQuestion("钻石应该怎么挖？"));

        assertTrue(FactualityGate.isActionTool("gather"));
        assertTrue(FactualityGate.isActionTool("craft"));
        assertFalse(FactualityGate.isActionTool("finish"));
        assertFalse(FactualityGate.isActionTool("scan_surroundings"));
        assertFalse(FactualityGate.isActionTool("get_task_status"));
    }

    @Test
    void blocksUnbackedPromisesButAllowsARealClarification() {
        FactualityGate.Context context = new FactualityGate.Context(
                "去标记处砍一棵树", true, false, false, false);

        assertTrue(FactualityGate.isUnbackedActionCommitment(context, "好，我马上去砍。"));
        assertFalse(FactualityGate.isUnbackedActionCommitment(context, "标记在哪个位置？"));
    }
}
