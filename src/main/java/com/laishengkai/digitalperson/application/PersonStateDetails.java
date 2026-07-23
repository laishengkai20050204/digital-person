package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable application-layer view of the current persisted short-term state. */
public record PersonStateDetails(
        PersonId personId,
        long version,
        PersonStateSnapshot state,
        Instant lastUpdatedAt,
        List<RegisteredStateEffect> activeEffects
) {
    public PersonStateDetails {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        state = Objects.requireNonNull(state, "state cannot be null");
        activeEffects = List.copyOf(Objects.requireNonNull(
                activeEffects,
                "activeEffects cannot be null"
        ));
    }

    public static PersonStateDetails from(VersionedPerson versionedPerson) {
        VersionedPerson source = Objects.requireNonNull(
                versionedPerson,
                "versionedPerson cannot be null"
        );
        StateEvolutionContext context = source.person().getStateEvolutionContext();
        List<RegisteredStateEffect> effects = context.effects().values().stream()
                .sorted(Comparator.comparing(RegisteredStateEffect::effectId))
                .toList();
        return new PersonStateDetails(
                source.person().getId(),
                source.version(),
                source.person().getStateSnapshot(),
                context.lastUpdatedAt(),
                effects
        );
    }
}
