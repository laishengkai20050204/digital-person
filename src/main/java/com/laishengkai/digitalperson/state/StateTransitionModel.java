package com.laishengkai.digitalperson.state;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Applies normalized exponential transitions to short-term state. */
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
        if (!StateTransition.isValidShape(shape)) {
            throw new IllegalArgumentException(
                    "shape must be finite, non-zero and within supported bounds"
            );
        }
        double target = shape > 0.0
                ? requestedDimension.getMaximum()
                : requestedDimension.getMinimum();
        return calculate(
                requestedDimension,
                current,
                target,
                Math.abs(shape),
                elapsed
        );
    }

    /**
     * Moves {@code current} toward an arbitrary in-range target using the same hourly
     * exponential model used by signed event effects.
     */
    public double calculate(
            StateDimension dimension,
            double current,
            double target,
            double hourlyRate,
            Duration elapsed
    ) {
        StateDimension requestedDimension = Objects.requireNonNull(
                dimension,
                "dimension cannot be null"
        );
        requireInRange(requestedDimension, current, "current");
        requireInRange(requestedDimension, target, "target");
        if (!Double.isFinite(hourlyRate) || hourlyRate <= 0.0) {
            throw new IllegalArgumentException("hourlyRate must be finite and positive");
        }

        Duration elapsedTime = Objects.requireNonNull(elapsed, "elapsed cannot be null");
        if (elapsedTime.isNegative()) {
            throw new IllegalArgumentException("elapsed cannot be negative");
        }
        if (elapsedTime.isZero() || current == target) {
            return current;
        }

        double decay = Math.exp(-hourlyRate * toHours(elapsedTime));
        return requestedDimension.clamp(target + (current - target) * decay);
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

    void applyTarget(
            PersonState state,
            StateDimension dimension,
            double target,
            double hourlyRate,
            Duration elapsed
    ) {
        PersonState currentState = Objects.requireNonNull(state, "state cannot be null");
        StateDimension requestedDimension = Objects.requireNonNull(
                dimension,
                "dimension cannot be null"
        );
        double nextValue = calculate(
                requestedDimension,
                requestedDimension.read(currentState),
                target,
                hourlyRate,
                elapsed
        );
        requestedDimension.write(currentState, nextValue);
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

    private static void requireInRange(
            StateDimension dimension,
            double value,
            String name
    ) {
        if (!dimension.contains(value)) {
            throw new IllegalArgumentException(
                    name + " must be between " + dimension.getMinimum()
                            + " and " + dimension.getMaximum()
            );
        }
    }

    private static double toHours(Duration duration) {
        return duration.toSeconds() / 3_600.0
                + duration.toNanosPart() / 3_600_000_000_000.0;
    }
}
