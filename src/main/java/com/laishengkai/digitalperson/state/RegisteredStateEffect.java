package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.EventId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One independently managed state effect, optionally caused by an event. */
public record RegisteredStateEffect(
        EffectId effectId,
        EventId sourceEventId,
        StateEffectType type,
        String cause,
        Instant startsAt,
        StateEffectEndPolicy endPolicy,
        Instant fixedEndsAt,
        List<StateTransition> transitions
) implements StateEffect {
    public RegisteredStateEffect {
        effectId = Objects.requireNonNull(effectId, "effectId cannot be null");
        type = Objects.requireNonNull(type, "type cannot be null");
        cause = requireText(cause, "cause");
        startsAt = Objects.requireNonNull(startsAt, "startsAt cannot be null");
        endPolicy = Objects.requireNonNull(endPolicy, "endPolicy cannot be null");
        transitions = new StateEffectDraft(
                type,
                cause,
                transitions,
                endPolicy,
                endPolicy == StateEffectEndPolicy.EVENT_END
                        ? java.time.Duration.ZERO
                        : duration(startsAt, fixedEndsAt)
        ).transitions();

        boolean eventBound = endPolicy == StateEffectEndPolicy.EVENT_END
                || endPolicy == StateEffectEndPolicy.EVENT_END_OR_FIXED_TIME;
        if (eventBound && sourceEventId == null) {
            throw new IllegalArgumentException(
                    endPolicy + " effects require a sourceEventId"
            );
        }
        if (endPolicy == StateEffectEndPolicy.EVENT_END && fixedEndsAt != null) {
            throw new IllegalArgumentException("EVENT_END effects cannot have fixedEndsAt");
        }
        if (endPolicy != StateEffectEndPolicy.EVENT_END) {
            Objects.requireNonNull(fixedEndsAt, "fixedEndsAt cannot be null");
            if (!fixedEndsAt.isAfter(startsAt)) {
                throw new IllegalArgumentException("fixedEndsAt must be after startsAt");
            }
        }
    }

    public static RegisteredStateEffect fromDraft(
            StateEffectDraft draft,
            EventId sourceEventId,
            Instant startsAt
    ) {
        StateEffectDraft safeDraft = Objects.requireNonNull(draft, "draft cannot be null");
        Instant start = Objects.requireNonNull(startsAt, "startsAt cannot be null");
        Instant fixedEnd = safeDraft.endPolicy() == StateEffectEndPolicy.EVENT_END
                ? null
                : start.plus(safeDraft.duration());
        return new RegisteredStateEffect(
                EffectId.random(),
                sourceEventId,
                safeDraft.type(),
                safeDraft.cause(),
                start,
                safeDraft.endPolicy(),
                fixedEnd,
                safeDraft.transitions()
        );
    }

    public Optional<EventId> sourceEvent() {
        return Optional.ofNullable(sourceEventId);
    }

    public Optional<Instant> fixedEndTime() {
        return Optional.ofNullable(fixedEndsAt);
    }

    public Optional<Instant> effectiveEndTime(Map<EventId, Instant> eventEndTimes) {
        Map<EventId, Instant> safeEndTimes = Objects.requireNonNull(
                eventEndTimes,
                "eventEndTimes cannot be null"
        );
        Instant eventEnd = sourceEventId == null ? null : safeEndTimes.get(sourceEventId);
        return switch (endPolicy) {
            case EVENT_END -> Optional.ofNullable(eventEnd);
            case FIXED_TIME -> Optional.of(fixedEndsAt);
            case EVENT_END_OR_FIXED_TIME -> Optional.of(
                    eventEnd == null || fixedEndsAt.isBefore(eventEnd)
                            ? fixedEndsAt
                            : eventEnd
            );
        };
    }

    public boolean isActiveAt(Instant time, Map<EventId, Instant> eventEndTimes) {
        Instant requestedTime = Objects.requireNonNull(time, "time cannot be null");
        if (requestedTime.isBefore(startsAt)) {
            return false;
        }
        return effectiveEndTime(eventEndTimes)
                .map(end -> requestedTime.isBefore(end))
                .orElse(true);
    }

    private static java.time.Duration duration(Instant start, Instant end) {
        Objects.requireNonNull(end, "fixedEndsAt cannot be null");
        return java.time.Duration.between(start, end);
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
}
