package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentitySnapshot;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable application-layer view of one persisted digital person. */
public record PersonDetails(
        PersonId personId,
        long version,
        PersonIdentitySnapshot identity,
        PersonalitySnapshot personality,
        PersonStateSnapshot state,
        int personEventCount,
        int userEventCount,
        Instant stateLastUpdatedAt,
        List<RegisteredStateEffect> activeEffects
) {
    public PersonDetails {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        identity = Objects.requireNonNull(identity, "identity cannot be null");
        personality = Objects.requireNonNull(personality, "personality cannot be null");
        state = Objects.requireNonNull(state, "state cannot be null");
        if (personEventCount < 0 || userEventCount < 0) {
            throw new IllegalArgumentException("event counts cannot be negative");
        }
        activeEffects = List.copyOf(Objects.requireNonNull(
                activeEffects,
                "activeEffects cannot be null"
        ));
    }

    public static PersonDetails from(VersionedPerson versionedPerson) {
        return from(versionedPerson, Clock.systemUTC());
    }

    public static PersonDetails from(
            VersionedPerson versionedPerson,
            Clock clock
    ) {
        VersionedPerson source = Objects.requireNonNull(
                versionedPerson,
                "versionedPerson cannot be null"
        );
        Clock effectiveClock = Objects.requireNonNull(clock, "clock cannot be null");
        Person person = source.person();
        StateEvolutionContext evolutionContext = person.getStateEvolutionContext();
        List<RegisteredStateEffect> effects = evolutionContext.effects().values().stream()
                .sorted(Comparator.comparing(RegisteredStateEffect::effectId))
                .toList();
        return new PersonDetails(
                person.getId(),
                source.version(),
                PersonIdentitySnapshot.from(
                        person.getIdentity(),
                        effectiveClock.instant()
                ),
                PersonalitySnapshot.from(person.getPersonality()),
                person.getStateSnapshot(),
                person.getPersonTimeline().getAll().size(),
                person.getUserTimeline().getAll().size(),
                evolutionContext.lastUpdatedAt(),
                effects
        );
    }
}
