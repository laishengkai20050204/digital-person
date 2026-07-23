package com.laishengkai.digitalperson.support;

import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectDraft;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateTransition;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Test-only builders for unified state-effect evaluator results. */
public final class StateEffectTestFixtures {
    private StateEffectTestFixtures() {
    }

    public static EventStateImpact eventBoundImpact(StateTransition... transitions) {
        Map<StateEffectType, List<StateTransition>> grouped = new EnumMap<>(StateEffectType.class);
        for (StateTransition transition : List.of(transitions)) {
            StateTransition value = Objects.requireNonNull(transition, "transition cannot be null");
            grouped.computeIfAbsent(typeOf(value.dimension()), ignored -> new ArrayList<>())
                    .add(value);
        }
        return new EventStateImpact(grouped.entrySet().stream()
                .map(entry -> StateEffectDraft.eventBound(
                        entry.getKey(),
                        "Test event-bound effect",
                        entry.getValue()
                ))
                .toList());
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
