package com.laishengkai.digitalperson.experience;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Getter
@ToString
@EqualsAndHashCode
public final class PersonEvent {

    private final ActivityType activityType;
    private final String title;
    private final String location;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final List<String> participants;
    private final String notes;

    public PersonEvent(
            ActivityType activityType,
            String title,
            String location,
            LocalDateTime startTime,
            LocalDateTime endTime,
            List<String> participants,
            String notes
    ) {
        this.activityType = Objects.requireNonNull(
                activityType,
                "activityType cannot be null"
        );
        this.title = requireText(title, "title");
        this.location = normalize(location);
        this.startTime = Objects.requireNonNull(
                startTime,
                "startTime cannot be null"
        );
        this.endTime = Objects.requireNonNull(
                endTime,
                "endTime cannot be null"
        );
        this.participants = normalizeParticipants(participants);
        this.notes = normalize(notes);

        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }
    }

    public PersonEvent(
            ActivityType activityType,
            String title,
            String location,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        this(
                activityType,
                title,
                location,
                startTime,
                endTime,
                List.of(),
                ""
        );
    }

    public boolean contains(LocalDateTime time) {
        Objects.requireNonNull(time, "time cannot be null");
        return !time.isBefore(startTime) && time.isBefore(endTime);
    }

    public EventStatus getStatusAt(LocalDateTime time) {
        Objects.requireNonNull(time, "time cannot be null");

        if (time.isBefore(startTime)) {
            return EventStatus.PLANNED;
        }
        if (time.isBefore(endTime)) {
            return EventStatus.IN_PROGRESS;
        }
        return EventStatus.COMPLETED;
    }

    public boolean overlaps(PersonEvent other) {
        Objects.requireNonNull(other, "other cannot be null");
        return startTime.isBefore(other.endTime)
                && other.startTime.isBefore(endTime);
    }

    private static List<String> normalizeParticipants(List<String> participants) {
        if (participants == null) {
            return List.of();
        }

        return participants.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
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
}
