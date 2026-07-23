package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.EventId;

import java.util.List;
import java.util.Objects;

/** Application-owned registration produced after one event evaluation completes. */
public record EventEffectRegistration(
        EventId eventId,
        List<RegisteredStateEffect> effects
) {
    public EventEffectRegistration {
        eventId = Objects.requireNonNull(eventId, "eventId cannot be null");
        effects = List.copyOf(Objects.requireNonNull(effects, "effects cannot be null"));
        for (RegisteredStateEffect effect : effects) {
            RegisteredStateEffect nonNullEffect = Objects.requireNonNull(
                    effect,
                    "effect cannot be null"
            );
            if (!eventId.equals(nonNullEffect.sourceEventId())) {
                throw new IllegalArgumentException(
                        "registered effect sourceEventId must match evaluated event"
                );
            }
        }
    }
}
