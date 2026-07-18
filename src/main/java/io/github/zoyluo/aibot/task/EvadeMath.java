package io.github.zoyluo.aibot.task;

final class EvadeMath {
    private EvadeMath() {
    }

    static double safeDistance(double startingDistance) {
        return Math.max(18.0D, Math.min(24.0D, Math.max(0.0D, startingDistance) + 10.0D));
    }

    static double progress(double startingDistance, double bestDistance, double safeDistance) {
        if (bestDistance >= safeDistance) {
            return 1.0D;
        }
        double span = Math.max(1.0D, safeDistance - startingDistance);
        double gained = Math.max(0.0D, bestDistance - startingDistance);
        return Math.min(0.95D, gained / span);
    }
}
