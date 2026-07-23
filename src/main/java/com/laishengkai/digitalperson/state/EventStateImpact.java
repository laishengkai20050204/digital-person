package com.laishengkai.digitalperson.state;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Model-evaluated independent effects caused by one event. */
public record EventStateImpact(List<StateEffectDraft> effects) {
    public EventStateImpact {
        effects = List.copyOf(Objects.requireNonNull(effects, "effects cannot be null"));
        for (StateEffectDraft effect : effects) {
            Objects.requireNonNull(effect, "effect cannot be null");
        }
    }

    /** Compatibility adapter for legacy evaluators that return event-bound transitions only. */
    public static EventStateImpact activeOnly(List<StateTransition> transitions) {
        List<StateTransition> safeTransitions = List.copyOf(Objects.requireNonNull(
                transitions,
                "transitions cannot be null"
        ));
        if (safeTransitions.isEmpty()) {
            return none();
        }

        Map<StateEffectType, List<StateTransition>> grouped = new EnumMap<>(
                StateEffectType.class
        );
        for (StateTransition transition : safeTransitions) {
            StateTransition nonNullTransition = Objects.requireNonNull(
                    transition,
                    "transition cannot be null"
            );
            StateEffectType type = typeOf(nonNullTransition.dimension());
            grouped.computeIfAbsent(type, ignored -> new ArrayList<>())
                    .add(nonNullTransition);
        }

        List<StateEffectDraft> drafts = grouped.entrySet().stream()
                .map(entry -> StateEffectDraft.eventBound(
                        entry.getKey(),
                        "Legacy evaluator event-bound effect",
                        entry.getValue()
                ))
                .toList();
        return new EventStateImpact(drafts);
    }

    public static EventStateImpact none() {
        return new EventStateImpact(List.of());
    }

    private static StateEffectType typeOf(StateDimension dimension) {
        return switch (dimension) {
            case VALENCE, ENERGY, TENSION -> StateEffectType.EMOTIONAL;
            case FOCUS, MENTAL_LOAD, MOTIVATION -> StateEffectType.COGNITIVE;
            case FATIGUE, SLEEPINESS, HUNGER -> StateEffectType.PHYSICAL;
            case LONELINESS, SOCIAL_NEED -> StateEffectType.SOCIAL;
        };
    }
}
