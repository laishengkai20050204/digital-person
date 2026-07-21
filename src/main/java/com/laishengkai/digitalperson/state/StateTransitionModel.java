package com.laishengkai.digitalperson.state;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Applies signed normalized exponential transitions to short-term state. */
public final class StateTransitionModel {
    public double calculate(
            StateDimension dimension,
            double current,
            double shape,
            Duration elapsed
    ) {
        StateDimension requestedDimension = Objects.requireNonNull(
                dimension,
                "dimension cannot be null"
        );
        if (!requestedDimension.contains(current)) {
            throw new IllegalArgumentException(
                    "current must be between "
                            + requestedDimension.getMinimum()
                            + " and "
                            + requestedDimension.getMaximum()
            );
        }
        if (!Double.isFinite(shape) || shape == 0.0) {
            throw new IllegalArgumentException("shape must be finite and non-zero");
        }

        Duration elapsedTime = Objects.requireNonNull(elapsed, "elapsed cannot be null");
        if (elapsedTime.isNegative()) {
            throw new IllegalArgumentException("elapsed cannot be negative");
        }
        if (elapsedTime.isZero()) {
            return current;
        }

        double minimum = requestedDimension.getMinimum();
        double maximum = requestedDimension.getMaximum();
        double normalized = (current - minimum) / (maximum - minimum);
        double decay = Math.exp(-Math.abs(shape) * toHours(elapsedTime));
        double nextNormalized = shape > 0.0
                ? 1.0 - (1.0 - normalized) * decay
                : normalized * decay;

        return requestedDimension.clamp(
                minimum + (maximum - minimum) * nextNormalized
        );
    }

    void apply(PersonState state, StateTransition transition, Duration elapsed) {
        PersonState currentState = Objects.requireNonNull(state, "state cannot be null");
        StateTransition requestedTransition = Objects.requireNonNull(
                transition,
                "transition cannot be null"
        );
        StateDimension dimension = requestedTransition.dimension();
        double nextValue = calculate(
                dimension,
                dimension.read(currentState),
                requestedTransition.shape(),
                elapsed
        );
        dimension.write(currentState, nextValue);
    }

    void applyAll(
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
