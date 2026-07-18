package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvadeMathTest {
    @Test
    void requiresAUsefulButBoundedGapFromTheThreat() {
        assertEquals(18.0D, EvadeMath.safeDistance(4.0D));
        assertEquals(18.0D, EvadeMath.safeDistance(8.0D));
        assertEquals(20.0D, EvadeMath.safeDistance(10.0D));
        assertEquals(24.0D, EvadeMath.safeDistance(20.0D));
        assertEquals(24.0D, EvadeMath.safeDistance(30.0D));
    }

    @Test
    void progressTracksDistanceGainedInsteadOfElapsedTime() {
        assertEquals(0.0D, EvadeMath.progress(8.0D, 8.0D, 18.0D));
        assertEquals(0.5D, EvadeMath.progress(8.0D, 13.0D, 18.0D));
        assertEquals(0.95D, EvadeMath.progress(8.0D, 17.5D, 18.0D));
        assertEquals(1.0D, EvadeMath.progress(8.0D, 18.0D, 18.0D));
    }
}
