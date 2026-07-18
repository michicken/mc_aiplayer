package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.UUID;

public final class MoveTask extends AbstractTask {
    // —— 分段中继导航(绕大湖/大障碍)——
    // 失败机理(real_nav_far 实测):目标 120 格外隔着大湖。单次 A* 的预算不够搜索整条绕湖长路，
    // 若直接朝目标挖会毁掉地形并可能进水。这里把长距离移动拆成安全的陆地中继段。
    // 解法:把"一步直达"拆成"多段经停"——每段 ≤40 格,A* 预算内必然可解;中继点沿 bot→goal
    // 方位角左右扫偏角,只选"干燥可站"的落脚点,湖是绕出来的,不是挖出来的。
    private static final int WAYPOINT_MAX_HOPS = 6;             // 中继跳数上限:防湖湾地形里无限折返
    private static final double WAYPOINT_ARRIVE_SQUARED = 9.0D; // 距中继点 ≤3 格即视为经停到达
    private static final int WAYPOINT_PATH_ATTEMPTS = 5;        // 单次选点最多对几个候选实跑 A*(同步寻路单次有 50ms 级预算,封顶防单 tick 长卡)
    private static final int[] WAYPOINT_DEFLECTIONS_DEG = {0, 30, -30, 60, -60, 90, -90}; // 偏角序列:先直奔,再左右扇形扫开

    private BlockPos goal;
    private final double startDistance;
    private final UUID targetPlayerUuid;
    private final String targetPlayerName;
    private BlockPos resolvedGoal;
    private BlockPos waypoint;                             // 经停模式:当前中继点;null = 直奔最终 goal
    private int waypointHops;                              // 已采用中继点次数(上限 WAYPOINT_MAX_HOPS)
    private int nextPlayerRepathTick;
    private int nextWaterRetryTick;

    public MoveTask(BlockPos start, BlockPos goal) {
        this(start, goal, null, "");
    }

    private MoveTask(BlockPos start, BlockPos goal, UUID targetPlayerUuid, String targetPlayerName) {
        this.goal = goal.toImmutable();
        this.startDistance = Math.sqrt(start.getSquaredDistance(goal));
        this.targetPlayerUuid = targetPlayerUuid;
        this.targetPlayerName = targetPlayerName == null ? "" : targetPlayerName;
    }

    public MoveTask(AIPlayerEntity bot, BlockPos goal) {
        this(bot.getBlockPos(), goal);
    }

    /** One-shot approach to a moving player. Completion uses live entity distance, not a stale block snapshot. */
    public MoveTask(AIPlayerEntity bot, ServerPlayerEntity target) {
        this(bot.getBlockPos(), target.getBlockPos(), target.getUuid(), target.getGameProfile().getName());
    }

    @Override
    public String name() {
        return "move";
    }

    @Override
    public String describe() {
        if (targetPlayerUuid != null) {
            return "Moving to " + targetPlayerName;
        }
        return "Walking to " + compact(goal);
    }

    @Override
    public double progress() {
        if (startDistance <= 0.1D || state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, elapsed / Math.max(20.0D, startDistance * 12.0D));
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        // 越界目标快速认输:y 超出世界范围(虚空下/建筑上限外)物理不可达,任何走/挖都是空转
        //(实测朝 y330 目标"挖天"耗满 2400t 不认输——空转是实操里最隐蔽的故障形态)。
        ServerWorld world = bot.getServerWorld();
        int bottom = world.getBottomY();
        int top = bottom + world.getHeight();
        if (goal.getY() < bottom || goal.getY() >= top) {
            fail("goal_out_of_world y=" + goal.getY());
            return;
        }
        if (!refreshPlayerGoal(bot, true)) {
            return;
        }
        nextPlayerRepathTick = 20;
        startNavigation(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        startNavigation(bot);
    }

    private void startNavigation(AIPlayerEntity bot) {
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (result.isFailed()) {
            if (isWaterGoal(bot)) {
                // 水面目标只允许正常水路；路径暂时不可得时等待后重试。
                waypoint = null;
                resolvedGoal = null;
                nextWaterRetryTick = Math.max(nextWaterRetryTick, elapsed + 40);
                BotLog.action(bot, "move_water_path_retry", "goal", compact(goal), "reason", result.reason());
                return;
            }
            // 使用陆地中继绕大障碍；没有可行路线时明确结束，绝不盲目挖穿世界。
            if (tryWaypointRelay(bot, "path_start:" + result.reason())) {
                return;
            }
            failNoSafeRoute(bot, result.reason());
            return;
        }
        waypoint = null; // 直达寻路成功 → 不需要经停(也清掉 resume 残留的旧中继)
        resolvedGoal = bot.getActionPack().activePathGoal();
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerPlayerEntity playerTarget = currentPlayerTarget(bot);
        if (targetPlayerUuid != null) {
            if (playerTarget == null || playerTarget.getServerWorld() != bot.getServerWorld()) {
                bot.getActionPack().stopAll();
                fail("target_player_offline_or_other_dimension");
                return;
            }
            if (bot.distanceTo(playerTarget) <= 3.0D) {
                bot.getActionPack().stopAll();
                complete();
                return;
            }
            BlockPos liveGoal = playerTarget.getBlockPos();
            if (goal.getSquaredDistance(liveGoal) > 4.0D && elapsed >= nextPlayerRepathTick) {
                retargetPlayer(bot, liveGoal);
                return;
            }
        } else if (bot.getBlockPos().getSquaredDistance(currentGoal()) <= 2.25D) {
            complete();
            return;
        }
        // 经停模式:正赶往中继点。到达中继点 ≠ 任务完成,在 waypointTick 里换乘(重新直奔最终 goal)。
        if (waypoint != null) {
            waypointTick(bot);
            return;
        }
        // 路径执行器提前空闲意味着当前路线已经失效。先尝试中继绕行；不能得到一条完整路径时
        // 宁可明确失败，也不朝目标坐标盲挖。后者会破坏建筑并把 bot 送进水体或洞穴。
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 5) {
            if (isWaterGoal(bot)) {
                if (elapsed >= nextWaterRetryTick) {
                    nextWaterRetryTick = elapsed + 40;
                    startNavigation(bot);
                }
                if (elapsed > 1200) {
                    fail("move_water_path_timeout");
                }
                return;
            }
            if (!tryWaypointRelay(bot, "path_idle")) {
                failNoSafeRoute(bot, "path_idle");
            }
            return;
        }
        if (elapsed > 1200) {
            fail("move_timeout");
        }
    }

    private void retargetPlayer(AIPlayerEntity bot, BlockPos liveGoal) {
        bot.getActionPack().stopAll();
        goal = liveGoal.toImmutable();
        resolvedGoal = null;
        waypoint = null;
        waypointHops = 0;
        nextPlayerRepathTick = elapsed + 20;
        startNavigation(bot);
        BotLog.action(bot, "move_player_retarget", "player", targetPlayerName, "goal", compact(goal));
    }

    private boolean refreshPlayerGoal(AIPlayerEntity bot, boolean failWhenMissing) {
        if (targetPlayerUuid == null) {
            return true;
        }
        ServerPlayerEntity target = currentPlayerTarget(bot);
        if (target == null || target.getServerWorld() != bot.getServerWorld()) {
            if (failWhenMissing) {
                fail("target_player_offline_or_other_dimension");
            }
            return false;
        }
        goal = target.getBlockPos().toImmutable();
        return true;
    }

    private ServerPlayerEntity currentPlayerTarget(AIPlayerEntity bot) {
        return targetPlayerUuid == null ? null : bot.getServer().getPlayerManager().getPlayer(targetPlayerUuid);
    }

    private boolean isWaterGoal(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        return Standability.isSwimmable(world, goal)
                || world.getFluidState(goal).isIn(FluidTags.WATER)
                || world.getFluidState(goal.down()).isIn(FluidTags.WATER);
    }

    // ==================== 分段中继导航 ====================

    /**
     * 经停模式主循环:到达中继点(≤3 格)或这一段路提前走断(执行器空闲)→ 清掉中继点,
     * 重新对最终 goal 直达寻路;直达仍不通就再选下一个中继点,逐段啃完全程。
     */
    private void waypointTick(AIPlayerEntity bot) {
        if (elapsed > 1200) {
            fail("move_timeout"); // 与纯寻路模式同一条总闸,经停绕路也不许无限耗
            return;
        }
        boolean arrived = bot.getBlockPos().getSquaredDistance(waypoint) <= WAYPOINT_ARRIVE_SQUARED;
        if (!arrived && !bot.getActionPack().isPathExecutorIdle()) {
            return; // 仍在赶往中继点的路上
        }
        // 经停到达(或这一段提前断了也就地换乘):重新直奔最终 goal——离湖更近、视角变了,直达可能已经可解。
        waypoint = null;
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (!result.isFailed()) {
            resolvedGoal = bot.getActionPack().activePathGoal();
            return;
        }
        if (tryWaypointRelay(bot, "relay_next:" + result.reason())) {
            return;
        }
        failNoSafeRoute(bot, "waypoint_exhausted");
    }

    private void failNoSafeRoute(AIPlayerEntity bot, String reason) {
        bot.getActionPack().stopAll();
        BotLog.action(bot, "move_no_safe_route", "goal", compact(goal), "reason", reason,
                "hops", waypointHops);
        fail("move_no_safe_route: " + reason);
    }

    /**
     * 尝试进入/延续经停模式:选一个干燥可站的中继点并对它寻路成功 → 记录状态 + 打点。
     * 返回 false 表示中继救不了(跳数耗尽或扇形里选不出落脚点),调用方走原失败路径。
     */
    private boolean tryWaypointRelay(AIPlayerEntity bot, String reason) {
        if (waypointHops >= WAYPOINT_MAX_HOPS) {
            BotLog.action(bot, "move_waypoint_exhausted",
                    "why", "hops_limit", "hops", waypointHops, "goal", compact(goal), "reason", reason);
            return false;
        }
        BlockPos picked = pickWaypoint(bot, goal);
        if (picked == null) {
            BotLog.action(bot, "move_waypoint_exhausted",
                    "why", "no_candidate", "hops", waypointHops, "goal", compact(goal), "reason", reason);
            return false;
        }
        waypoint = picked;
        waypointHops++;
        BotLog.action(bot, "move_waypoint",
                "to", waypoint.toShortString(), "hop", waypointHops, "goal", compact(goal), "reason", reason);
        return true;
    }

    /**
     * 中继点选择:以 bot→goal 方位角 θ 为基准,偏角 {0°,±30°,±60°,±90°}(外层)×
     * 前出距离 {goal距离一半钳到≤40, 24, 12}(内层)生成候选;每个候选取地表落脚 y,
     * 必须同时满足:可站立 + 干列(湖面/浅滩水点全排除)+ 不比当前更远离 goal 超 10%(防背向倒退)。
     * 第一个几何合格且 startPathTo 不失败的候选即采用(寻路成功即顺带启动了去程)。
     * 距离钳 ≤40:保证每一段都落在 A* 步行预算(10k 节点)稳定可解的范围内——分段正是为此。
     */
    private BlockPos pickWaypoint(AIPlayerEntity bot, BlockPos target) {
        ServerWorld world = bot.getServerWorld();
        double bx = bot.getX();
        double bz = bot.getZ();
        double dxGoal = target.getX() + 0.5D - bx;
        double dzGoal = target.getZ() + 0.5D - bz;
        double goalDist = Math.sqrt(dxGoal * dxGoal + dzGoal * dzGoal);
        if (goalDist < 1.0D) {
            return null; // 已经贴脸,没有"前出中继"可言
        }
        double theta = Math.atan2(dzGoal, dxGoal);
        double maxGoalDist = goalDist * 1.10D;
        double maxGoalDistSq = maxGoalDist * maxGoalDist;
        double[] distances = {Math.min(goalDist / 2.0D, 40.0D), 24.0D, 12.0D};
        int pathAttempts = 0;
        for (int deg : WAYPOINT_DEFLECTIONS_DEG) {
            double phi = theta + Math.toRadians(deg);
            double cos = Math.cos(phi);
            double sin = Math.sin(phi);
            for (double dist : distances) {
                int x = (int) Math.floor(bx + dist * cos);
                int z = (int) Math.floor(bz + dist * sin);
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (!Standability.isStandable(world, candidate)) {
                    continue;
                }
                if (!isDryColumn(world, candidate)) {
                    continue; // 湖面取出的悬空格/浅滩水脚全排除——中继点自己先别站进水里
                }
                if (candidate.getSquaredDistance(target) > maxGoalDistSq) {
                    continue;
                }
                if (pathAttempts >= WAYPOINT_PATH_ATTEMPTS) {
                    return null; // 同步 A* 单次 ~50ms 级,封顶实跑次数防单 tick 长卡
                }
                pathAttempts++;
                if (!bot.getActionPack().startPathTo(candidate).isFailed()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * 干列检查:候选脚格及其向下 4 格全部无流体才算"干"。
     * MOTION_BLOCKING_NO_LEAVES 在湖面上取到的是水面上方的悬空格、在浅滩取到的脚格本身是水,
     * 两类都必须排除——否则中继点把 bot 直接引进水里,绕行变送死。
     */
    private static boolean isDryColumn(ServerWorld world, BlockPos feet) {
        for (int i = 0; i <= 4; i++) {
            if (!world.getFluidState(feet.down(i)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private BlockPos currentGoal() {
        return resolvedGoal == null ? goal : resolvedGoal;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
