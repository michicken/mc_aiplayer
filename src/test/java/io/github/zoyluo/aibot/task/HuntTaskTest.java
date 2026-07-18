package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuntTaskTest {
    @Test
    void normalizesTinyGroundContactDriftWithoutMovingRealFractionalPositions() {
        assertEquals(72, TaskPositionMath.stableFeetY(71.999999D));
        assertEquals(72, TaskPositionMath.stableFeetY(72.0D));
        assertEquals(71, TaskPositionMath.stableFeetY(71.80D));
    }

    @Test
    void rejectsRoamTargetsOnDisconnectedTerrainFarBelowAHighPlatform() {
        assertTrue(TaskPositionMath.isPlausibleRoamHeight(232, 232, 16));
        assertTrue(TaskPositionMath.isPlausibleRoamHeight(70, 108, 32));
        assertFalse(TaskPositionMath.isPlausibleRoamHeight(232, 70, 32));
        assertFalse(TaskPositionMath.isPlausibleRoamHeight(70, 120, 32));
    }
}
