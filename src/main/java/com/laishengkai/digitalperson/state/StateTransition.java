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

    public StateTransition {
        Objects.requireNonNull(dimension, "dimension cannot be null");

        if (!Double.isFinite(shape) || shape == 0.0) {
            throw new IllegalArgumentException("shape must be finite and non-zero");
        }
    }
}
