package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Stable JSON document persisted for one complete digital-person aggregate.
 *
 * <p>This type belongs exclusively to the MySQL adapter. Domain objects remain
 * unaware of Jackson, JDBC, database columns and document-schema evolution.</p>
 */
record PersonAggregateDocument(
        int schemaVersion,
        String personId,
        PersonalityDocument personality,
        StateDocument state,
        List<EventDocument> personEvents,
        List<EventDocument> userEvents,
        StateEvolutionDocument stateEvolution
) {
    PersonAggregateDocument {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        personId = requireText(personId, "personId");
        personality = Objects.requireNonNull(personality, "personality cannot be null");
        state = Objects.requireNonNull(state, "state cannot be null");
        personEvents = copy(personEvents, "personEvents");
        userEvents = copy(userEvents, "userEvents");
        stateEvolution = Objects.requireNonNull(
                stateEvolution,
                "stateEvolution cannot be null"
        );
    }

    record PersonalityDocument(
            double honestyHumility,
            double emotionality,
            double extraversion,
            double agreeableness,
            double conscientiousness,
            double openness
    ) {
    }

    record StateDocument(
            double valence,
            double energy,
            double tension,
            double focus,
            double mentalLoad,
            double motivation,
            double fatigue,
            double sleepiness,
            double hunger,
            double loneliness,
            double socialNeed
    ) {
    }

    record EventDocument(
            String eventId,
            String activityType,
            String title,
            String location,
            Instant startTime,
            Instant endTime,
            List<String> participants,
            String notes,
            String endReason
    ) {
        EventDocument {
            eventId = requireText(eventId, "eventId");
            activityType = requireText(activityType, "activityType");
            title = requireText(title, "title");
            location = normalize(location);
            startTime = Objects.requireNonNull(startTime, "startTime cannot be null");
            participants = copy(participants, "participants");
            notes = normalize(notes);
            endReason = normalizeNullable(endReason);
            if ((endTime == null) != (endReason == null)) {
                throw new IllegalArgumentException(
                        "endTime and endReason must either both be present or both be absent"
                );
            }
        }
    }

    record StateEvolutionDocument(
            Instant lastUpdatedAt,
            List<ChannelEffectDocument> channelEffects
    ) {
        StateEvolutionDocument {
            channelEffects = copy(channelEffects, "channelEffects");
        }
    }

    record ChannelEffectDocument(
            String channel,
            String eventId,
            List<TransitionDocument> transitions
    ) {
        ChannelEffectDocument {
            channel = requireText(channel, "channel");
            eventId = requireText(eventId, "eventId");
            transitions = copy(transitions, "transitions");
        }
    }

    record TransitionDocument(String dimension, double shape) {
        TransitionDocument {
            dimension = requireText(dimension, "dimension");
            if (!Double.isFinite(shape) || shape == 0.0) {
                throw new IllegalArgumentException("shape must be finite and non-zero");
            }
        }
    }

    private static <T> List<T> copy(List<T> values, String fieldName) {
        List<T> copied = List.copyOf(Objects.requireNonNull(
                values,
                fieldName + " cannot be null"
        ));
        if (copied.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(fieldName + " cannot contain null");
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

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
