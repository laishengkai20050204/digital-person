package com.laishengkai.digitalperson.state;

import java.util.List;
import java.util.Objects;

/** Model-evaluated independent effects caused by one event. */
public record EventStateImpact(List<StateEffectDraft> effects) {
    public EventStateImpact {
        effects = List.copyOf(Objects.requireNonNull(effects, "effects cannot be null"));
        for (StateEffectDraft effect : effects) {
            Objects.requireNonNull(effect, "effect cannot be null");
        }
    }

    public static EventStateImpact none() {
        return new EventStateImpact(List.of());
    }
}
