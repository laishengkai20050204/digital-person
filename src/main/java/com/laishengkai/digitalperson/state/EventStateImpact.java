package com.laishengkai.digitalperson.state;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Model-evaluated impact of one event while active and after it ends. */
public record EventStateImpact(
        List<StateTransition> activeTransitions,
        AftermathStateEffectPlan aftermath
) {
    public EventStateImpact {
        activeTransitions = copyActiveTransitions(activeTransitions);
        aftermath = Objects.requireNonNull(aftermath, "aftermath cannot be null");
    }

    public static EventStateImpact activeOnly(List<StateTransition> transitions) {
        return new EventStateImpact(transitions, AftermathStateEffectPlan.none());
    }

    public static EventStateImpact none() {
        return activeOnly(List.of());
    }

    private static List<StateTransition> copyActiveTransitions(
            List<StateTransition> requestedTransitions
    ) {
        List<StateTransition> copied = List.copyOf(Objects.requireNonNull(
                requestedTransitions,
                "activeTransitions cannot be null"
        ));
        Set<StateDimension> dimensions = EnumSet.noneOf(StateDimension.class);
        for (StateTransition transition : copied) {
            StateTransition nonNullTransition = Objects.requireNonNull(
                    transition,
                    "active transition cannot be null"
            );
            if (!dimensions.add(nonNullTransition.dimension())) {
                throw new IllegalArgumentException(
                        "only one active transition is allowed per state dimension"
                );
            }
        }
        return copied;
    }
}
