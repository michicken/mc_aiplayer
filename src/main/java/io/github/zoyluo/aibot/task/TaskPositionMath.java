package io.github.zoyluo.aibot.task;

final class TaskPositionMath {
    private TaskPositionMath() {
    }

    static int stableFeetY(double boundingBoxMinY) {
        return (int) Math.floor(boundingBoxMinY + 0.05D);
    }

    /** Reject obviously disconnected altitude jumps before paying for a full A* search. */
    static boolean isPlausibleRoamHeight(int fromY, int toY, int horizontalDistance) {
        int allowedDelta = Math.max(24, Math.max(0, horizontalDistance) + 8);
        return Math.abs(toY - fromY) <= allowedDelta;
    }
}
