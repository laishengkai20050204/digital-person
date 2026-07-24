package com.laishengkai.digitalperson.state;

import java.util.Objects;

/**
 * Describes one state dimension changing along the normalized exponential curve.
 *
 * <p>A positive {@code shape} moves the value toward the dimension maximum.
 * A negative {@code shape} moves it toward the dimension minimum. A larger
 * absolute value means faster change.</p>
 */
public record StateTransition(
        StateDimension dimension,
        double shape
) {

    /**
     * Maximum supported hourly exponential rate in either direction.
     *
     * <p>Continuous model effects normally stay at or below {@code 1.2}. The higher
     * hard bound exists only for the explicitly short-lived {@code INSTANT} intensity,
     * which can approach a bound within several minutes.</p>
     */
    public static final double MAX_ABSOLUTE_SHAPE = 36.0;

    public StateTransition {
        Objects.requireNonNull(dimension, "dimension cannot be null");

        if (!isValidShape(shape)) {
            throw new IllegalArgumentException(
                    "shape must be finite, non-zero and within [-"
                            + MAX_ABSOLUTE_SHAPE + ", "
                            + MAX_ABSOLUTE_SHAPE + "]"
            );
        }
    }

    public static boolean isValidShape(double shape) {
        return Double.isFinite(shape)
                && shape != 0.0
                && Math.abs(shape) <= MAX_ABSOLUTE_SHAPE;
    }
}
