package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.EventId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral semantic view of one effect active at evaluation time. */
public record ActiveStateEffectSnapshot(
        StateEffectType type,
        String cause,
        String sourceEventId,
        Instant startsAt,
        StateEffectEndPolicy endPolicy,
        Instant effectiveEndsAt,
        List<StateTransition> transitions
) {
    public ActiveStateEffectSnapshot {
        type = Objects.requireNonNull(type, "type cannot be null");
        cause = requireText(cause, "cause");
        sourceEventId = normalizeNullable(sourceEventId);
        startsAt = Objects.requireNonNull(startsAt, "startsAt cannot be null");
        endPolicy = Objects.requireNonNull(endPolicy, "endPolicy cannot be null");
        transitions = List.copyOf(Objects.requireNonNull(
                transitions,
                "transitions cannot be null"
        ));
        if (transitions.isEmpty()) {
            throw new IllegalArgumentException("transitions cannot be empty");
        }
        if (transitions.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("transitions cannot contain null");
        }
    }

    public static ActiveStateEffectSnapshot from(
            RegisteredStateEffect effect,
            Map<EventId, Instant> eventEndTimes
    ) {
        RegisteredStateEffect source = Objects.requireNonNull(
                effect,
                "effect cannot be null"
        );
        Map<EventId, Instant> endTimes = Objects.requireNonNull(
                eventEndTimes,
                "eventEndTimes cannot be null"
        );
        return new ActiveStateEffectSnapshot(
                source.type(),
                source.cause(),
                source.sourceEventId() == null
                        ? null
                        : source.sourceEventId().toString(),
                source.startsAt(),
                source.endPolicy(),
                source.effectiveEndTime(endTimes).orElse(null),
                source.transitions()
        );
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

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
