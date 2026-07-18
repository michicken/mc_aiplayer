package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.BedBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.LinkedList;
import java.util.OptionalInt;
import java.util.Queue;

public final class EmergencyShelterTask extends AbstractTask {
    private final Queue<BlockPos> targets = new LinkedList<>();
    private int placed;

    @Override
    public String name() {
        return "shelter";
    }

    @Override
    public String describe() {
        return "Emergency shelter placed=" + placed + " remaining=" + targets.size();
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        int total = placed + targets.size();
        return total == 0 ? 0.0D : Math.min(0.95D, (double) placed / total);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        targets.clear();
        BlockPos feet = bot.getBlockPos();
        BlockPos head = feet.up();
        targets.add(head.up());
        for (Direction direction : Direction.Type.HORIZONTAL) {
            targets.add(feet.offset(direction));
            targets.add(head.offset(direction));
        }
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 120) {
            fail("shelter_timeout placed=" + placed + " remaining=" + targets.size());
            return;
        }
        OptionalInt blockSlot = findShelterBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            fail("missing shelter_block");
            return;
        }
        while (!targets.isEmpty() && !bot.getServerWorld().getBlockState(targets.peek()).isAir()) {
            targets.poll();
        }
        if (targets.isEmpty()) {
            complete();
            return;
        }
        if (InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
            fail("cannot_equip_shelter_block");
            return;
        }
        ActionResult result = BuildAction.placeBlockAt(bot, targets.peek());
        if (result.isSuccess()) {
            placed++;
            targets.poll();
        }
        if (targets.isEmpty()) {
            complete();
        }
    }

    private static OptionalInt findShelterBlockSlot(AIPlayerEntity bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            var stack = inventory.main.get(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (stack.isOf(Items.TORCH) || blockItem.getBlock() instanceof BedBlock) {
                continue;
            }
            if (!blockItem.getBlock().getDefaultState().isAir()) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public static boolean hasShelterBlock(AIPlayerEntity bot) {
        return findShelterBlockSlot(bot).isPresent();
    }
}
