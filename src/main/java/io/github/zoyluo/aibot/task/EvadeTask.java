package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EvadeTask extends AbstractTask {
    private final Threat threat;
    private BlockPos escapeGoal;
    private boolean tracksThreatDistance;
    private double startingThreatDistance;
    private double currentThreatDistance;
    private double bestThreatDistance;
    private double safeThreatDistance;

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
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (tracksThreatDistance) {
            return EvadeMath.progress(startingThreatDistance, bestThreatDistance, safeThreatDistance);
        }
        return 0.0D;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        tracksThreatDistance = threat.entity() != null || threat.pos() != null;
        if (tracksThreatDistance) {
            startingThreatDistance = horizontalDistance(bot.getPos(), threatPosition());
            currentThreatDistance = startingThreatDistance;
            bestThreatDistance = startingThreatDistance;
            safeThreatDistance = EvadeMath.safeDistance(startingThreatDistance);
        }
        if (startEscape(bot)) {
            // 逃命必须冲刺:走路 4.3m/s 对僵尸追击 4.0m/s 只快一线,寻路绕障/起步延迟就被贴脸磨死
            //(实测无装备 bot 夜间远征被僵尸追杀致死)。冲刺 5.6m/s 才能真正甩开。
            bot.getActionPack().setSprinting(true);
        }
        // escapeGoal==null(无可达路线)→ 不启动寻路,onTick 首 tick 即 fail 交危险层升级。
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (threat.entity() != null && !threat.entity().isAlive()) {
            completeEscape(bot);
            return;
        }
        if (tracksThreatDistance) {
            currentThreatDistance = horizontalDistance(bot.getPos(), threatPosition());
            if (currentThreatDistance > bestThreatDistance + 0.5D) {
                bestThreatDistance = currentThreatDistance;
            }
            if (currentThreatDistance >= safeThreatDistance) {
                completeEscape(bot);
                return;
            }
        }
        if (escapeGoal == null) {
            // 无处可逃(深处隧道/被围)→ 干净失败,交 DangerWatcher 升级筑墙自保,不假完成空转挨打。
            failEscape(bot, "no_valid_escape_route");
            return;
        }
        bot.getActionPack().setSprinting(true); // 持续保持(其他控制器可能每 tick 复位)
        if (bot.getBlockPos().getSquaredDistance(escapeGoal) <= 6.25D) {
            if (!tracksThreatDistance) {
                completeEscape(bot);
                return;
            }
            // A reachable fallback is only one leg of the escape. Reaching it is not evidence that
            // the threat has been shaken; keep extending the route until the measured gap is safe.
            escapeGoal = null;
            if (!startEscape(bot)) {
                failEscape(bot, "no_valid_escape_route");
            }
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            if (!startEscape(bot)) {
                failEscape(bot, "no_valid_escape_route");
                return;
            }
        }
        if (elapsed > 400) {
            failEscape(bot, "evade_timeout");
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().setSprinting(false);
        bot.getActionPack().stopAll();
    }

    private void completeEscape(AIPlayerEntity bot) {
        bot.getActionPack().setSprinting(false);
        bot.getActionPack().stopAll();
        complete();
    }

    private void failEscape(AIPlayerEntity bot, String reason) {
        bot.getActionPack().setSprinting(false);
        bot.getActionPack().stopAll();
        fail(reason);
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
        double currentDistance = horizontalDistance(bot.getPos(), threatPos);
        List<BlockPos> direct = escapeCandidates(bot, base, threatPos, 4, 0.0D, 2);
        List<BlockPos> nearby = escapeCandidates(
                bot, bot.getBlockPos(), threatPos, 8, currentDistance + 1.5D, 3);

        // Underground, a point twenty blocks away is usually behind solid rock. Try an actual side
        // passage first; outdoors, preserve the long sprint as the natural first choice.
        boolean underground = !bot.getServerWorld().isSkyVisible(bot.getBlockPos());
        if (underground) {
            return tryCandidates(bot, nearby) || tryCandidates(bot, direct);
        }
        return tryCandidates(bot, direct) || tryCandidates(bot, nearby);
    }

    private boolean tryCandidates(AIPlayerEntity bot, List<BlockPos> candidates) {
        for (BlockPos candidate : candidates) {
            if (tryStartPath(bot, candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryStartPath(AIPlayerEntity bot, BlockPos candidate) {
        if (candidate == null) {
            return false;
        }
        ActionResult result = bot.getActionPack().startEscapePathTo(candidate);
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

    private static List<BlockPos> escapeCandidates(AIPlayerEntity bot,
                                                   BlockPos center,
                                                   Vec3d threatPos,
                                                   int radiusLimit,
                                                   double minimumThreatDistance,
                                                   int limit) {
        List<EscapeCandidate> ranked = new ArrayList<>();
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
                ranked.add(new EscapeCandidate(candidate.toImmutable(), score));
            }
        }
        ranked.sort(Comparator.comparingDouble(EscapeCandidate::score).reversed());
        List<BlockPos> selected = new ArrayList<>(limit);
        for (EscapeCandidate candidate : ranked) {
            boolean repeatsDirection = selected.stream()
                    .anyMatch(existing -> existing.isWithinDistance(candidate.pos(), 3.5D));
            if (repeatsDirection) {
                continue;
            }
            selected.add(candidate.pos());
            if (selected.size() >= limit) {
                break;
            }
        }
        return selected;
    }

    private record EscapeCandidate(BlockPos pos, double score) {
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
