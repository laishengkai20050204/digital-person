package com.laishengkai.digitalperson.experience;

import lombok.ToString;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An activity that actually happened or is currently happening.
 */
@ToString
public final class PersonEvent {

    private final EventId id;
    private final ActivityType activityType;
    private final String title;
    private final String location;
    private final TimeRange timeRange;
    private final List<String> participants;
    private final String notes;

    private EventEndReason endReason;

    public PersonEvent(
            EventId id,
            ActivityType activityType,
            String title,
            String location,
            TimeRange timeRange,
            List<String> participants,
            String notes
    ) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
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
                EventId.random(),
                activityType,
                title,
                location,
                timeRange,
                List.of(),
                ""
        );
    }

    public EventId getId() {
        return id;
    }

    public ActivityType getActivityType() {
        return activityType;
    }

    public ActivityChannel getChannel() {
        return activityType.getChannel();
    }

    public String getTitle() {
        return title;
    }

    public String getLocation() {
        return location;
    }

    public TimeRange getTimeRange() {
        return timeRange;
    }

    public List<String> getParticipants() {
        return participants;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getStartTime() {
        return timeRange.getStart();
    }

    public Optional<Instant> getEndTime() {
        return timeRange.getEnd();
    }

    public Optional<EventEndReason> getEndReason() {
        return Optional.ofNullable(endReason);
    }

    public boolean isOpen() {
        return timeRange.isOpenEnded() && endReason == null;
    }

    public boolean isFinished() {
        return endReason != null;
    }

    public boolean contains(Instant time) {
        return timeRange.contains(time);
    }

    /**
     * Returns no status before the event actually began.
     */
    public Optional<EventStatus> getStatusAt(Instant time) {
        Objects.requireNonNull(time, "time cannot be null");

        if (time.isBefore(getStartTime())) {
            return Optional.empty();
        }

        return Optional.of(
                timeRange.contains(time)
                        ? EventStatus.IN_PROGRESS
                        : EventStatus.FINISHED
        );
    }

    public boolean overlaps(PersonEvent other) {
        Objects.requireNonNull(other, "other cannot be null");
        return timeRange.overlaps(other.timeRange);
    }

    public boolean overlaps(TimeRange other) {
        return timeRange.overlaps(Objects.requireNonNull(other, "other cannot be null"));
    }

    public void finish(Instant endTime, EventEndReason reason) {
        Objects.requireNonNull(endTime, "endTime cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        ensureNotFinished();
        if (!timeRange.isOpenEnded()) {
            throw new IllegalStateException("only an open event can be finished explicitly");
        }

        timeRange.finish(endTime);
        endReason = reason;
    }

    public void markFinished(EventEndReason reason) {
        Objects.requireNonNull(reason, "reason cannot be null");
        ensureNotFinished();

        if (timeRange.getEnd().isEmpty()) {
            throw new IllegalStateException("an open event must be finished with an end time");
        }
        endReason = reason;
    }

    private void ensureNotFinished() {
        if (endReason != null) {
            throw new IllegalStateException("event has already finished");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PersonEvent event)) {
            return false;
        }
        return id.equals(event.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
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
