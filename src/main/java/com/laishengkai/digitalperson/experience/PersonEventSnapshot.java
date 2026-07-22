package com.laishengkai.digitalperson.experience;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable event representation used in model-evaluation context. */
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
        String endReason
) {
    public PersonEventSnapshot {
        owner = Objects.requireNonNull(owner, "owner cannot be null");
        eventId = requireText(eventId, "eventId");
        activityType = requireText(activityType, "activityType");
        channel = requireText(channel, "channel");
        title = requireText(title, "title");
        location = Objects.requireNonNullElse(location, "").strip();
        startTime = Objects.requireNonNull(startTime, "startTime cannot be null");
        participants = List.copyOf(Objects.requireNonNullElse(participants, List.of()));
        notes = Objects.requireNonNullElse(notes, "").strip();
        endReason = endReason == null ? null : endReason.strip();
    }

    public static PersonEventSnapshot from(Owner owner, PersonEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        return new PersonEventSnapshot(
                owner,
                event.getId().toString(),
                event.getActivityType().name(),
                event.getChannel().name(),
                event.getTitle(),
                event.getLocation(),
                event.getStartTime(),
                event.getEndTime().orElse(null),
                event.getParticipants(),
                event.getNotes(),
                event.getEndReason().map(Enum::name).orElse(null)
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

    public enum Owner {
        PERSON,
        USER
    }
}
