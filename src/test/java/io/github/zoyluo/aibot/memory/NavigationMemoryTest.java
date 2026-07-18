package io.github.zoyluo.aibot.memory;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationMemoryTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void suppressesOnlyTheSameNearbyRouteDuringItsShortBackoff() {
        UUID bot = UUID.randomUUID();
        BlockPos from = new BlockPos(3, 64, 3);
        BlockPos goal = new BlockPos(100, 64, 100);
        NavigationMemory.INSTANCE.rememberFailure(bot, OVERWORLD, from, goal, 100);

        assertTrue(NavigationMemory.INSTANCE.recentlyFailed(bot, OVERWORLD,
                new BlockPos(7, 65, 6), new BlockPos(103, 64, 102), 200));
        assertFalse(NavigationMemory.INSTANCE.recentlyFailed(bot, OVERWORLD,
                new BlockPos(20, 64, 3), goal, 200));
        assertFalse(NavigationMemory.INSTANCE.recentlyFailed(bot, OVERWORLD, from, goal, 701));
    }

    @Test
    void aSuccessfulPathClearsTheRecordedFailure() {
        UUID bot = UUID.randomUUID();
        BlockPos from = new BlockPos(-3, 70, -3);
        BlockPos goal = new BlockPos(-30, 70, -30);
        NavigationMemory.INSTANCE.rememberFailure(bot, OVERWORLD, from, goal, 100);
        NavigationMemory.INSTANCE.recordSuccess(bot, OVERWORLD, from, goal);

        assertFalse(NavigationMemory.INSTANCE.recentlyFailed(bot, OVERWORLD, from, goal, 101));
    }
}
