package com.laishengkai.digitalperson.state;

import java.util.Objects;

/**
 * Describes one state dimension moving toward a target value.
 *
 * <p>{@code shape} is a positive speed measured per hour. The target decides
 * whether the value rises or falls.</p>
 */
public record StateTransition(
        StateDimension dimension,
        double target,
        double shape
) {

    public StateTransition {
        Objects.requireNonNull(dimension, "dimension cannot be null");

        if (!dimension.contains(target)) {
            throw new IllegalArgumentException(
                    "target must be between "
                            + dimension.getMinimum()
                            + " and "
                            + dimension.getMaximum()
            );
        }

        if (!Double.isFinite(shape) || shape <= 0.0) {
            throw new IllegalArgumentException("shape must be a finite positive value");
        }
    }
}
