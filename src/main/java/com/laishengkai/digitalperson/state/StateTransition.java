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
     * <p>A magnitude of {@code 3.0} already moves a value roughly 95% of the
     * remaining distance toward its bound in one hour. Larger values add little
     * useful expressiveness while making model errors disproportionately severe.</p>
     */
    public static final double MAX_ABSOLUTE_SHAPE = 3.0;

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
