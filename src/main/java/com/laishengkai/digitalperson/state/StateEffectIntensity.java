package com.laishengkai.digitalperson.state;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Semantic model-facing intensity tier mapped to a deterministic hourly exponential rate.
 *
 * <p>The ranges deliberately remain narrow. The deterministic jitter prevents every event
 * from evolving identically while keeping retries, restarts and tests reproducible.</p>
 */
public enum StateEffectIntensity {
    LOW(0.08, 0.12),
    MEDIUM(0.20, 0.30),
    HIGH(0.40, 0.60),
    EXTREME(0.80, 1.20),
    INSTANT(24.0, 36.0);

    private final double minimumRate;
    private final double maximumRate;

    StateEffectIntensity(double minimumRate, double maximumRate) {
        this.minimumRate = minimumRate;
        this.maximumRate = maximumRate;
    }

    public double minimumRate() {
        return minimumRate;
    }

    public double maximumRate() {
        return maximumRate;
    }

    public double resolve(StateEffectDirection direction, String stableSeed) {
        Objects.requireNonNull(direction, "direction cannot be null");
        String seed = Objects.requireNonNull(stableSeed, "stableSeed cannot be null");
        double unit = stableUnitInterval(seed);
        double magnitude = minimumRate + (maximumRate - minimumRate) * unit;
        return direction.apply(magnitude);
    }

    private static double stableUnitInterval(String seed) {
        byte[] bytes = seed.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        long positive = hash & Long.MAX_VALUE;
        return positive / (double) Long.MAX_VALUE;
    }
}
