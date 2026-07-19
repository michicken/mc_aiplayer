package io.github.zoyluo.aibot.pathfinding;

import java.util.Locale;

/** Pure retry policy shared by the navigation facade and its unit tests. */
public final class NavigationRetryPolicy {
    private static final int MAX_FAILURES = 6;
    private static final int MAX_COOLDOWN_TICKS = 240;

    private NavigationRetryPolicy() {
    }

    public static String failureKind(String reason) {
        String text = reason == null ? "unknown" : reason.toLowerCase(Locale.ROOT);
        if (text.contains("dig_in_water")) {
            return "dig_in_water";
        }
        if (text.contains("no_progress") || text.contains("stuck")) {
            return "no_progress";
        }
        if (text.contains("timeout")) {
            return "timeout";
        }
        if (text.contains("no_start")) {
            return "no_start";
        }
        if (text.contains("goal_unreachable") || text.contains("goal_not_standable")) {
            return "unreachable";
        }
        if (text.contains("danger")) {
            return "danger";
        }
        return "other";
    }

    public static int nextFailureCount(String previousKind, String currentKind, boolean nearbyGoal, int previousCount) {
        if (!nearbyGoal || previousKind == null || !previousKind.equals(currentKind)) {
            return 1;
        }
        return Math.min(MAX_FAILURES, Math.max(1, previousCount + 1));
    }

    public static int cooldownTicks(String failureKind, int consecutiveFailures) {
        int base = "dig_in_water".equals(failureKind) ? 80 : 40;
        int shift = Math.max(0, Math.min(3, consecutiveFailures - 1));
        return Math.min(MAX_COOLDOWN_TICKS, base << shift);
    }
}
