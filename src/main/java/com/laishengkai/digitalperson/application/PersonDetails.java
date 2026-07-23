package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Immutable application-layer view of one persisted digital person. */
public record PersonDetails(
        PersonId personId,
        long version,
        PersonalitySnapshot personality,
        PersonStateSnapshot state,
        int personEventCount,
        int userEventCount,
        Instant stateLastUpdatedAt,
        Set<ActivityChannel> activeEffectChannels,
        int residualEffectCount
) {
    public PersonDetails {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        personality = Objects.requireNonNull(personality, "personality cannot be null");
        state = Objects.requireNonNull(state, "state cannot be null");
        if (personEventCount < 0 || userEventCount < 0) {
            throw new IllegalArgumentException("event counts cannot be negative");
        }
        activeEffectChannels = Set.copyOf(Objects.requireNonNull(
                activeEffectChannels,
                "activeEffectChannels cannot be null"
        ));
        if (residualEffectCount < 0) {
            throw new IllegalArgumentException("residualEffectCount cannot be negative");
        }
    }

    public static PersonDetails from(VersionedPerson versionedPerson) {
        VersionedPerson source = Objects.requireNonNull(
                versionedPerson,
                "versionedPerson cannot be null"
        );
        Person person = source.person();
        StateEvolutionContext evolutionContext = person.getStateEvolutionContext();
        return new PersonDetails(
                person.getId(),
                source.version(),
                PersonalitySnapshot.from(person.getPersonality()),
                person.getStateSnapshot(),
                person.getPersonTimeline().getAll().size(),
                person.getUserTimeline().getAll().size(),
                evolutionContext.lastUpdatedAt(),
                evolutionContext.channelEffects().keySet(),
                evolutionContext.residualEffects().size()
        );
    }
}
