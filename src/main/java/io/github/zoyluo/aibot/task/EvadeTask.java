package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class EvadeTask extends AbstractTask {
    private final Threat threat;
    private BlockPos escapeGoal;

    public EvadeTask(Threat threat) {
        this.threat = threat;
    }

    @Override
    public String name() {
        return "evade";
    }

    @Override
    public String describe() {
        return "Evading " + threat.type() + " toward " + (escapeGoal == null ? "(pending)" : compact(escapeGoal));
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, elapsed / 160.0D);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (startEscape(bot)) {
            // 逃命必须冲刺:走路 4.3m/s 对僵尸追击 4.0m/s 只快一线,寻路绕障/起步延迟就被贴脸磨死
            //(实测无装备 bot 夜间远征被僵尸追杀致死)。冲刺 5.6m/s 才能真正甩开。
            bot.getActionPack().setSprinting(true);
        }
        // escapeGoal==null(无可达路线)→ 不启动寻路,onTick 首 tick 即 fail 交危险层升级。
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (escapeGoal == null) {
            // 无处可逃(深处隧道/被围)→ 干净失败,交 DangerWatcher 升级筑墙自保,不假完成空转挨打。
            fail("no_valid_escape_route");
            return;
        }
        bot.getActionPack().setSprinting(true); // 持续保持(其他控制器可能每 tick 复位)
        if (bot.getBlockPos().getSquaredDistance(escapeGoal) <= 6.25D) {
            bot.getActionPack().setSprinting(false);
            complete();
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            if (!startEscape(bot)) {
                fail("no_valid_escape_route");
                return;
            }
        }
        if (elapsed > 400) {
            bot.getActionPack().setSprinting(false);
            fail("evade_timeout");
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().setSprinting(false);
    }

    /**
     * 候选落脚点必须同时通过 A* 可达性验证。仅检查 Standability 会把岩壁另一侧的干地当逃跑点，
     * PathExecutor 随后立刻空闲，再反复选择同一个点。先远撤，远撤路径不可得时再走近处安全通道。
     */
    private boolean startEscape(AIPlayerEntity bot) {
        Vec3d threatPos = threatPosition();
        Vec3d away = bot.getPos().subtract(threatPos);
        if (away.lengthSquared() < 0.01D) {
            away = new Vec3d(1.0D, 0.0D, 0.0D);
        }
        // 12→20 格:原 12 格停下时怪仍在感知圈内,evade 完成→任务 resume→再触发 evade,
        // 反复被蹭血磨死。20 格出圈,一次逃干净。
        away = away.normalize().multiply(20.0D);
        BlockPos base = BlockPos.ofFloored(bot.getPos().add(away));
        BlockPos direct = bestEscapeNear(bot, base, threatPos, 4, 0.0D);
        if (tryStartPath(bot, direct)) {
            return true;
        }

        // 矿洞里“离怪 20 格”的目标常正好落在实心岩壁。旧实现直接失败，DangerWatcher 下次扫描
        // 又派同一个 EvadeTask，形成 no_valid_escape_route 刷屏。退一步：在脚下周围找一条能实质
        // 拉开距离的安全通道，哪怕只能先撤 4~8 格，也比站在原地等下一次扫描更像真人逃跑。
        double currentDistance = horizontalDistance(bot.getPos(), threatPos);
        BlockPos nearby = bestEscapeNear(bot, bot.getBlockPos(), threatPos, 8, currentDistance + 1.5D);
        return tryStartPath(bot, nearby);
    }

    private boolean tryStartPath(AIPlayerEntity bot, BlockPos candidate) {
        if (candidate == null) {
            return false;
        }
        ActionResult result = bot.getActionPack().startPathTo(candidate);
        if (result.isFailed()) {
            return false;
        }
        escapeGoal = candidate;
        return true;
    }

    private Vec3d threatPosition() {
        if (threat.entity() != null) {
            return threat.entity().getPos();
        }
        if (threat.pos() != null) {
            return Vec3d.ofCenter(threat.pos());
        }
        return Vec3d.ZERO;
    }

    private static BlockPos bestEscapeNear(AIPlayerEntity bot,
                                           BlockPos center,
                                           Vec3d threatPos,
                                           int radiusLimit,
                                           double minimumThreatDistance) {
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Vec3d botPos = bot.getPos();
        for (int radius = 0; radius <= radiusLimit; radius++) {
            for (BlockPos candidate : BlockPos.iterate(
                    center.add(-radius, -2, -radius), center.add(radius, 2, radius))) {
                if (Math.max(Math.abs(candidate.getX() - center.getX()), Math.abs(candidate.getZ() - center.getZ())) != radius
                        || !Standability.isStandable(bot.getServerWorld(), candidate)) {
                    continue;
                }
                Vec3d candidatePos = Vec3d.ofCenter(candidate);
                double threatDistance = horizontalDistance(candidatePos, threatPos);
                double travelDistance = horizontalDistance(candidatePos, botPos);
                if (travelDistance < 3.0D || threatDistance < minimumThreatDistance) {
                    continue;
                }
                // Prefer getting farther from danger, then prefer a short reachable first leg.
                double score = threatDistance * 4.0D - travelDistance * 0.15D;
                if (score > bestScore) {
                    best = candidate.toImmutable();
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static double horizontalDistance(Vec3d first, Vec3d second) {
        double x = first.x - second.x;
        double z = first.z - second.z;
        return Math.sqrt(x * x + z * z);
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
