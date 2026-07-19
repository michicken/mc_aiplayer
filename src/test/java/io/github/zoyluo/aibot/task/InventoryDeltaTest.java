package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryDeltaTest {
    @Test
    void existingInventoryDoesNotSatisfyANewGatherRequest() {
        assertEquals(0, InventoryDelta.since(27, 27));
        assertEquals(1, InventoryDelta.since(28, 27));
        assertEquals(0, InventoryDelta.since(12, 27));
    }
}
