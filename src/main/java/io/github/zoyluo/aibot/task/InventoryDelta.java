package io.github.zoyluo.aibot.task;

/** Counts items acquired after a task starts, ignoring the inventory it inherited. */
final class InventoryDelta {
    private InventoryDelta() {
    }

    static int since(int currentCount, int baselineCount) {
        return Math.max(0, currentCount - baselineCount);
    }
}
