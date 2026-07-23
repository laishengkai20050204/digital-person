package com.laishengkai.digitalperson.experience;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable, model-friendly event representation with derived timing information. */
public record PersonEventSnapshot(
        Owner owner,
        String eventId,
        String activityType,
        String channel,
        String title,
        String location,
        Instant startTime,
        Instant endTime,
        List<String> participants,
        String notes,
        String endReason,
        boolean active,
        long elapsedMinutes,
        Long minutesSinceEnd
) {
    public PersonEventSnapshot {
        owner = Objects.requireNonNull(owner, "owner cannot be null");
        eventId = requireText(eventId, "eventId");
        activityType = requireText(activityType, "activityType");
        channel = requireText(channel, "channel");
        title = requireText(title, "title");
        location = Objects.requireNonNullElse(location, "").strip();
        startTime = Objects.requireNonNull(startTime, "startTime cannot be null");
        if (endTime != null && endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("endTime cannot be before startTime");
        }
        participants = List.copyOf(Objects.requireNonNullElse(participants, List.of()));
        if (participants.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("participants cannot contain null");
        }
        notes = Objects.requireNonNullElse(notes, "").strip();
        endReason = endReason == null ? null : endReason.strip();
        if (elapsedMinutes < 0) {
            throw new IllegalArgumentException("elapsedMinutes cannot be negative");
        }
        if (active) {
            if (endTime != null || endReason != null || minutesSinceEnd != null) {
                throw new IllegalArgumentException(
                        "active events cannot have end data"
                );
            }
        } else {
            if (endTime == null) {
                throw new IllegalArgumentException(
                        "inactive events require endTime"
                );
            }
            if (minutesSinceEnd == null || minutesSinceEnd < 0) {
                throw new IllegalArgumentException(
                        "inactive events require non-negative minutesSinceEnd"
                );
            }
        }
    }

    /** Compatibility constructor for manually built diagnostic snapshots. */
    public PersonEventSnapshot(
            Owner owner,
            String eventId,
            String activityType,
            String channel,
            String title,
            String location,
            Instant startTime,
            Instant endTime,
            List<String> participants,
            String notes,
            String endReason
    ) {
        this(
                owner,
                eventId,
                activityType,
                channel,
                title,
                location,
                startTime,
                endTime,
                participants,
                notes,
                endReason,
                endTime == null,
                endTime == null
                        ? 0L
                        : nonNegativeMinutes(startTime, endTime),
                endTime == null ? null : 0L
        );
    }

    public static PersonEventSnapshot from(Owner owner, PersonEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        Instant referenceTime = event.getEndTime().orElse(event.getStartTime());
        return from(owner, event, referenceTime);
    }

    public static PersonEventSnapshot from(
            Owner owner,
            PersonEvent event,
            Instant evaluationTime
    ) {
        PersonEvent source = Objects.requireNonNull(event, "event cannot be null");
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        Instant endTime = source.getEndTime().orElse(null);
        boolean active = endTime == null;
        Instant elapsedEnd = active ? max(now, source.getStartTime()) : endTime;
        return new PersonEventSnapshot(
                owner,
                source.getId().toString(),
                source.getActivityType().name(),
                source.getChannel().name(),
                source.getTitle(),
                source.getLocation(),
                source.getStartTime(),
                endTime,
                source.getParticipants(),
                source.getNotes(),
                source.getEndReason().map(Enum::name).orElse(null),
                active,
                nonNegativeMinutes(source.getStartTime(), elapsedEnd),
                active ? null : nonNegativeMinutes(endTime, max(now, endTime))
        );
    }

    private static Instant max(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static long nonNegativeMinutes(Instant start, Instant end) {
        if (end.isBefore(start)) {
            return 0L;
        }
        return Duration.between(start, end).toMinutes();
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

    public enum Owner {
        PERSON,
        USER
    }
}
