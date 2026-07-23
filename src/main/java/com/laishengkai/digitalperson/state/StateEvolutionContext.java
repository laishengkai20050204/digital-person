package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.EventId;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable runtime state required to continue deterministic state evolution. */
public record StateEvolutionContext(
        Instant lastUpdatedAt,
        Map<EffectId, RegisteredStateEffect> effects,
        Set<EventId> evaluatedEventIds
) {
    public StateEvolutionContext(
            Instant lastUpdatedAt,
            Map<EffectId, RegisteredStateEffect> effects
    ) {
        this(lastUpdatedAt, effects, Set.of());
    }

    public StateEvolutionContext {
        Map<EffectId, RegisteredStateEffect> effectCopy = new HashMap<>();
        Objects.requireNonNull(effects, "effects cannot be null")
                .forEach((effectId, effect) -> {
                    EffectId nonNullId = Objects.requireNonNull(
                            effectId,
                            "effectId cannot be null"
                    );
                    RegisteredStateEffect nonNullEffect = Objects.requireNonNull(
                            effect,
                            "effect cannot be null"
                    );
                    if (!nonNullId.equals(nonNullEffect.effectId())) {
                        throw new IllegalArgumentException(
                                "effect id must match map key"
                        );
                    }
                    effectCopy.put(nonNullId, nonNullEffect);
                });
        effects = Map.copyOf(effectCopy);

        Set<EventId> evaluatedCopy = new HashSet<>();
        for (EventId eventId : Objects.requireNonNull(
                evaluatedEventIds,
                "evaluatedEventIds cannot be null"
        )) {
            evaluatedCopy.add(Objects.requireNonNull(
                    eventId,
                    "evaluated event id cannot be null"
            ));
        }
        evaluatedEventIds = Set.copyOf(evaluatedCopy);
    }

    public static StateEvolutionContext initial() {
        return new StateEvolutionContext(null, Map.of(), Set.of());
    }

    public Optional<Instant> previousUpdateTime() {
        return Optional.ofNullable(lastUpdatedAt);
    }
}
