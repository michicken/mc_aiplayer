package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelBrainAdvisorsTest {
    @Test
    void fansOutOneSharedFrameToFiveReadOnlyLanes() {
        List<ChatMessage> shared = List.of(
                ChatMessage.system("base rules"),
                ChatMessage.user("[owner] says: 去砍树\n\nCurrent state:{\"trees\":1}"));

        List<ParallelBrainAdvisors.LaneRequest> requests = ParallelBrainAdvisors.requests(shared, 42L);

        assertEquals(5, requests.size());
        assertTrue(requests.stream().allMatch(request -> request.history().contains(shared.get(1))));
        assertTrue(requests.stream().allMatch(request -> request.history().getLast().role().equals("system")));
        assertTrue(requests.stream().allMatch(request -> request.history().getLast().content().contains("frame=42")));
        assertTrue(requests.stream().allMatch(request -> request.history().stream()
                .noneMatch(message -> "tool".equals(message.role()) || !message.toolCalls().isEmpty())));
    }

    @Test
    void keepsVoiceOutOfMainDecisionDigestButPreservesItForSpeech() {
        List<ParallelBrainAdvisors.LaneResult> results = List.of(
                new ParallelBrainAdvisors.LaneResult("strategy", "用 gather 开始砍树", "", 1, 1),
                new ParallelBrainAdvisors.LaneResult("voice", "这棵我来处理。", "", 1, 1),
                new ParallelBrainAdvisors.LaneResult("critic", "未完成前不能说砍完", "", 1, 1));

        String digest = ParallelBrainAdvisors.mergeDigest(7L, results);

        assertTrue(digest.contains("用 gather"));
        assertTrue(digest.contains("不能说砍完"));
        assertFalse(digest.contains("这棵我来处理"));
        assertEquals("这棵我来处理。", ParallelBrainAdvisors.voiceSuggestion(results));
    }
}
