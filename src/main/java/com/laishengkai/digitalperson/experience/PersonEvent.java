package com.laishengkai.digitalperson.experience;

import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Getter
@ToString
public final class PersonEvent {

    private final ActivityType activityType;
    private final String title;
    private final String location;
    private final TimeRange timeRange;
    private final List<String> participants;
    private final String notes;

    public PersonEvent(
            ActivityType activityType,
            String title,
            String location,
            TimeRange timeRange,
            List<String> participants,
            String notes
    ) {
        this.activityType = Objects.requireNonNull(
                activityType,
                "activityType cannot be null"
        );
        this.title = requireText(title, "title");
        this.location = normalize(location);
        this.timeRange = Objects.requireNonNull(
                timeRange,
                "timeRange cannot be null"
        );
        this.participants = normalizeParticipants(participants);
        this.notes = normalize(notes);
    }

    public PersonEvent(
            ActivityType activityType,
            String title,
            String location,
            TimeRange timeRange
    ) {
        this(
                activityType,
                title,
                location,
                timeRange,
                List.of(),
                ""
        );
    }

    public PersonEvent(
            ActivityType activityType,
            String title,
            String location,
            LocalDateTime startTime,
            LocalDateTime endTime,
            List<String> participants,
            String notes
    ) {
        this(
                activityType,
                title,
                location,
                TimeRange.closed(startTime, endTime),
                participants,
                notes
        );
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
                TimeRange.closed(startTime, endTime)
        );
    }

    public static PersonEvent openEnded(
            ActivityType activityType,
            String title,
            String location,
            LocalDateTime startTime
    ) {
        return new PersonEvent(
                activityType,
                title,
                location,
                TimeRange.openEnded(startTime)
        );
    }

    public LocalDateTime getStartTime() {
        return timeRange.getStart();
    }

    public Optional<LocalDateTime> getEndTime() {
        return timeRange.getEnd();
    }

    public boolean contains(LocalDateTime time) {
        return timeRange.contains(time);
    }

    public EventStatus getStatusAt(LocalDateTime time) {
        return timeRange.getStatusAt(time);
    }

    public boolean overlaps(PersonEvent other) {
        Objects.requireNonNull(other, "other cannot be null");
        return timeRange.overlaps(other.timeRange);
    }

    public void finish(LocalDateTime endTime) {
        timeRange.finish(endTime);
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
