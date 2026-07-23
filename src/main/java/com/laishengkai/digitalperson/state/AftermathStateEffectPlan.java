package com.laishengkai.digitalperson.state;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A model-evaluated state effect that starts when its source activity ends.
 *
 * <p>The plan is stored with the active activity effect, then materialized as an
 * independent {@link ResidualStateEffect}. It does not occupy an activity
 * channel and therefore can overlap later activities and other aftermaths.</p>
 */
public record AftermathStateEffectPlan(
        Duration duration,
        List<StateTransition> transitions
) {
    public AftermathStateEffectPlan {
        duration = Objects.requireNonNull(duration, "duration cannot be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        transitions = copyTransitions(transitions);
        if (transitions.isEmpty() && !duration.isZero()) {
            throw new IllegalArgumentException(
                    "an empty aftermath must have zero duration"
            );
        }
        if (!transitions.isEmpty() && duration.isZero()) {
            throw new IllegalArgumentException(
                    "a non-empty aftermath must have positive duration"
            );
        }
    }

    public static AftermathStateEffectPlan none() {
        return new AftermathStateEffectPlan(Duration.ZERO, List.of());
    }

    public boolean isPresent() {
        return !transitions.isEmpty();
    }

    private static List<StateTransition> copyTransitions(
            List<StateTransition> requestedTransitions
    ) {
        List<StateTransition> copied = List.copyOf(Objects.requireNonNull(
                requestedTransitions,
                "transitions cannot be null"
        ));
        Set<StateDimension> dimensions = EnumSet.noneOf(StateDimension.class);
        for (StateTransition transition : copied) {
            StateTransition nonNullTransition = Objects.requireNonNull(
                    transition,
                    "transition cannot be null"
            );
            if (!dimensions.add(nonNullTransition.dimension())) {
                throw new IllegalArgumentException(
                        "only one aftermath transition is allowed per state dimension"
                );
            }
        }
        return copied;
    }
}
