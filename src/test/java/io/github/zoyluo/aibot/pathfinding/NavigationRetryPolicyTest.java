package io.github.zoyluo.aibot.pathfinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NavigationRetryPolicyTest {
    @Test
    void classifiesEquivalentWaterFailuresTogether() {
        assertEquals("dig_in_water", NavigationRetryPolicy.failureKind(
                "dig_in_water; replan_failed: GOAL_UNREACHABLE"));
        assertEquals("no_progress", NavigationRetryPolicy.failureKind(
                "walk_failed: stuck_blocked; replan_throttled"));
    }

    @Test
    void increasesBackoffOnlyForSameFailureNearSameGoal() {
        int first = NavigationRetryPolicy.nextFailureCount(null, "timeout", false, 0);
        int second = NavigationRetryPolicy.nextFailureCount("timeout", "timeout", true, first);
        int changedReason = NavigationRetryPolicy.nextFailureCount("timeout", "unreachable", true, second);
        int changedArea = NavigationRetryPolicy.nextFailureCount("timeout", "timeout", false, second);

        assertEquals(1, first);
        assertEquals(2, second);
        assertEquals(1, changedReason);
        assertEquals(1, changedArea);
        assertEquals(40, NavigationRetryPolicy.cooldownTicks("timeout", first));
        assertEquals(80, NavigationRetryPolicy.cooldownTicks("timeout", second));
        assertEquals(240, NavigationRetryPolicy.cooldownTicks("dig_in_water", 4));
    }
}
