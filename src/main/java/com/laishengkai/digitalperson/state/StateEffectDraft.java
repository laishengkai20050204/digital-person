package com.laishengkai.digitalperson.state;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Provider-neutral model output before application-owned identity and timestamps are assigned. */
public record StateEffectDraft(
        StateEffectType type,
        String cause,
        List<StateTransition> transitions,
        StateEffectEndPolicy endPolicy,
        Duration duration
) {
    public StateEffectDraft {
        type = Objects.requireNonNull(type, "type cannot be null");
        cause = requireText(cause, "cause");
        transitions = copyTransitions(type, transitions);
        endPolicy = Objects.requireNonNull(endPolicy, "endPolicy cannot be null");
        duration = Objects.requireNonNull(duration, "duration cannot be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        if (endPolicy == StateEffectEndPolicy.EVENT_END && !duration.isZero()) {
            throw new IllegalArgumentException("EVENT_END effects require zero duration");
        }
        if (endPolicy != StateEffectEndPolicy.EVENT_END && duration.isZero()) {
            throw new IllegalArgumentException("fixed-time effects require positive duration");
        }
    }

    public static StateEffectDraft eventBound(
            StateEffectType type,
            String cause,
            List<StateTransition> transitions
    ) {
        return new StateEffectDraft(
                type,
                cause,
                transitions,
                StateEffectEndPolicy.EVENT_END,
                Duration.ZERO
        );
    }

    private static List<StateTransition> copyTransitions(
            StateEffectType type,
            List<StateTransition> requestedTransitions
    ) {
        List<StateTransition> copied = List.copyOf(Objects.requireNonNull(
                requestedTransitions,
                "transitions cannot be null"
        ));
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("an effect must contain at least one transition");
        }
        Set<StateDimension> dimensions = EnumSet.noneOf(StateDimension.class);
        for (StateTransition transition : copied) {
            StateTransition nonNullTransition = Objects.requireNonNull(
                    transition,
                    "transition cannot be null"
            );
            if (!dimensions.add(nonNullTransition.dimension())) {
                throw new IllegalArgumentException(
                        "only one transition is allowed per state dimension in one effect"
                );
            }
            if (!type.supports(nonNullTransition.dimension())) {
                throw new IllegalArgumentException(
                        "effect type " + type + " does not support "
                                + nonNullTransition.dimension()
                );
            }
        }
        return copied;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }
}
