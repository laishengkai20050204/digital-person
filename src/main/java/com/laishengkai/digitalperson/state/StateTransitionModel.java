package com.laishengkai.digitalperson.state;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Applies the normalized exponential transition model to short-term state.
 *
 * <p>The model is:</p>
 *
 * <pre>
 * next = target + (current - target) * exp(-shape * elapsedHours)
 * </pre>
 *
 * <p>The current value determines the implicit starting point on the curve,
 * so callers do not need to store or calculate {@code t0} explicitly.</p>
 */
public final class StateTransitionModel {

    public double calculate(
            double current,
            double target,
            double shape,
            Duration elapsed
    ) {
        if (!Double.isFinite(current)) {
            throw new IllegalArgumentException("current must be finite");
        }
        if (!Double.isFinite(target)) {
            throw new IllegalArgumentException("target must be finite");
        }
        if (!Double.isFinite(shape) || shape <= 0.0) {
            throw new IllegalArgumentException("shape must be a finite positive value");
        }

        Duration elapsedTime = Objects.requireNonNull(elapsed, "elapsed cannot be null");
        if (elapsedTime.isNegative()) {
            throw new IllegalArgumentException("elapsed cannot be negative");
        }
        if (elapsedTime.isZero() || current == target) {
            return current;
        }

        double elapsedHours = toHours(elapsedTime);
        return target
                + (current - target)
                * Math.exp(-shape * elapsedHours);
    }

    public void apply(
            PersonState state,
            StateTransition transition,
            Duration elapsed
    ) {
        PersonState currentState = Objects.requireNonNull(state, "state cannot be null");
        StateTransition requestedTransition = Objects.requireNonNull(
                transition,
                "transition cannot be null"
        );

        StateDimension dimension = requestedTransition.dimension();
        double nextValue = calculate(
                dimension.read(currentState),
                requestedTransition.target(),
                requestedTransition.shape(),
                elapsed
        );

        dimension.write(currentState, nextValue);
    }

    /**
     * Applies one resolved transition per state dimension.
     */
    public void applyAll(
            PersonState state,
            Collection<StateTransition> transitions,
            Duration elapsed
    ) {
        Objects.requireNonNull(state, "state cannot be null");
        Collection<StateTransition> requestedTransitions = Objects.requireNonNull(
                transitions,
                "transitions cannot be null"
        );
        Objects.requireNonNull(elapsed, "elapsed cannot be null");

        Set<StateDimension> seenDimensions = EnumSet.noneOf(StateDimension.class);

        for (StateTransition transition : requestedTransitions) {
            StateTransition requestedTransition = Objects.requireNonNull(
                    transition,
                    "transition cannot be null"
            );

            if (!seenDimensions.add(requestedTransition.dimension())) {
                throw new IllegalArgumentException(
                        "only one transition is allowed per state dimension"
                );
            }
        }

        for (StateTransition transition : requestedTransitions) {
            apply(state, transition, elapsed);
        }
    }

    private static double toHours(Duration duration) {
        return duration.toSeconds() / 3_600.0
                + duration.toNanosPart() / 3_600_000_000_000.0;
    }
}
