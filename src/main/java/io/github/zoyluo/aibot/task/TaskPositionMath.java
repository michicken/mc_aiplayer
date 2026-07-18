package io.github.zoyluo.aibot.task;

final class TaskPositionMath {
    private TaskPositionMath() {
    }

    static int stableFeetY(double boundingBoxMinY) {
        return (int) Math.floor(boundingBoxMinY + 0.05D);
    }
}
