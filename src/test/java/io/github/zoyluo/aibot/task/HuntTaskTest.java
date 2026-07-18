package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HuntTaskTest {
    @Test
    void normalizesTinyGroundContactDriftWithoutMovingRealFractionalPositions() {
        assertEquals(72, TaskPositionMath.stableFeetY(71.999999D));
        assertEquals(72, TaskPositionMath.stableFeetY(72.0D));
        assertEquals(71, TaskPositionMath.stableFeetY(71.80D));
    }
}
