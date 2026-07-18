package io.github.zoyluo.aibot.memory;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短期导航经验:同一片起点到同一目标区域刚被证明没有安全路线时,别立刻原样再撞一次。
 *
 * 这不是持久知识。方块、门和玩家位置都可能变化,所以只保留很短时间并以区域而非精确格子匹配。
 * MoveTask 成功拿到路径会撤销相同路线的失败结论。
 */
public final class NavigationMemory {
    public static final NavigationMemory INSTANCE = new NavigationMemory();

    private static final int REGION_SIZE = 8;
    private static final long RETRY_BACKOFF_TICKS = 20L * 30L;
    private static final int PER_BOT_CAP = 24;

    private record RouteKey(String dimension, int fromX, int fromY, int fromZ, int goalX, int goalY, int goalZ) {
    }

    private record FailedRoute(RouteKey key, long expiresAt) {
    }

    private final Map<UUID, Deque<FailedRoute>> failedRoutes = new ConcurrentHashMap<>();

    private NavigationMemory() {
    }

    public boolean recentlyFailed(UUID botId, String dimension, BlockPos from, BlockPos goal, long tick) {
        Deque<FailedRoute> routes = failedRoutes.get(botId);
        if (routes == null) {
            return false;
        }
        RouteKey key = key(dimension, from, goal);
        synchronized (routes) {
            routes.removeIf(route -> route.expiresAt() <= tick);
            return routes.stream().anyMatch(route -> route.key().equals(key));
        }
    }

    public void rememberFailure(UUID botId, String dimension, BlockPos from, BlockPos goal, long tick) {
        Deque<FailedRoute> routes = failedRoutes.computeIfAbsent(botId, ignored -> new ArrayDeque<>());
        RouteKey key = key(dimension, from, goal);
        synchronized (routes) {
            routes.removeIf(route -> route.expiresAt() <= tick || route.key().equals(key));
            routes.addLast(new FailedRoute(key, tick + RETRY_BACKOFF_TICKS));
            while (routes.size() > PER_BOT_CAP) {
                routes.removeFirst();
            }
        }
    }

    public void recordSuccess(UUID botId, String dimension, BlockPos from, BlockPos goal) {
        Deque<FailedRoute> routes = failedRoutes.get(botId);
        if (routes == null) {
            return;
        }
        RouteKey key = key(dimension, from, goal);
        synchronized (routes) {
            routes.removeIf(route -> route.key().equals(key));
        }
    }

    /** 测试与 bot 生命周期清理使用;不落盘。 */
    public void clearFor(UUID botId) {
        failedRoutes.remove(botId);
    }

    private static RouteKey key(String dimension, BlockPos from, BlockPos goal) {
        return new RouteKey(dimension == null ? "" : dimension,
                region(from.getX()), region(from.getY()), region(from.getZ()),
                region(goal.getX()), region(goal.getY()), region(goal.getZ()));
    }

    private static int region(int coordinate) {
        return Math.floorDiv(coordinate, REGION_SIZE);
    }
}
