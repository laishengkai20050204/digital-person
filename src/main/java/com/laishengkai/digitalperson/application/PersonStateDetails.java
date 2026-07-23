package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Immutable application-layer view of the current persisted short-term state. */
public record PersonStateDetails(
        PersonId personId,
        long version,
        PersonStateSnapshot state,
        Instant lastUpdatedAt,
        Set<ActivityChannel> activeEffectChannels,
        int residualEffectCount
) {
    public PersonStateDetails {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        state = Objects.requireNonNull(state, "state cannot be null");
        activeEffectChannels = Set.copyOf(Objects.requireNonNull(
                activeEffectChannels,
                "activeEffectChannels cannot be null"
        ));
        if (residualEffectCount < 0) {
            throw new IllegalArgumentException("residualEffectCount cannot be negative");
        }
    }

    public static PersonStateDetails from(VersionedPerson versionedPerson) {
        VersionedPerson source = Objects.requireNonNull(
                versionedPerson,
                "versionedPerson cannot be null"
        );
        StateEvolutionContext context = source.person().getStateEvolutionContext();
        return new PersonStateDetails(
                source.person().getId(),
                source.version(),
                source.person().getStateSnapshot(),
                context.lastUpdatedAt(),
                context.channelEffects().keySet(),
                context.residualEffects().size()
        );
    }
}
