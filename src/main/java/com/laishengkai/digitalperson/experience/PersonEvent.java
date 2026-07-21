package com.laishengkai.digitalperson.experience;

import lombok.ToString;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A person activity with stable identity and lifecycle information.
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
    private Instant terminatedAt;

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

    public Optional<Instant> getTerminatedAt() {
        return Optional.ofNullable(terminatedAt);
    }

    public boolean isOpen() {
        return timeRange.isOpenEnded() && endReason == null;
    }

    public boolean isTerminated() {
        return endReason != null;
    }

    public boolean isCancelled() {
        return endReason == EventEndReason.CANCELLED;
    }

    public boolean contains(Instant time) {
        Objects.requireNonNull(time, "time cannot be null");

        if (time.isBefore(getStartTime())) {
            return false;
        }
        if (terminatedAt != null && !time.isBefore(terminatedAt)) {
            return false;
        }
        return timeRange.contains(time);
    }

    public EventStatus getStatusAt(Instant time) {
        Objects.requireNonNull(time, "time cannot be null");

        if (isCancelled() && !time.isBefore(terminatedAt)) {
            return EventStatus.CANCELLED;
        }
        if (time.isBefore(getStartTime())) {
            return EventStatus.PLANNED;
        }
        if (terminatedAt != null && !time.isBefore(terminatedAt)) {
            return EventStatus.FINISHED;
        }
        if (timeRange.contains(time)) {
            return EventStatus.IN_PROGRESS;
        }
        return EventStatus.FINISHED;
    }

    public boolean overlaps(PersonEvent other) {
        Objects.requireNonNull(other, "other cannot be null");
        if (!other.hasPositiveEffectiveDuration()) {
            return false;
        }
        return overlaps(other.getStartTime(), other.effectiveEndTime());
    }

    public boolean overlaps(TimeRange other) {
        Objects.requireNonNull(other, "other cannot be null");
        return overlaps(other.getStart(), other.getEnd());
    }

    public void finish(Instant endTime, EventEndReason reason) {
        Objects.requireNonNull(endTime, "endTime cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        if (reason == EventEndReason.CANCELLED) {
            throw new IllegalArgumentException("use cancel() for cancelled events");
        }
        ensureNotTerminated();
        if (!timeRange.isOpenEnded()) {
            throw new IllegalStateException("only an open event can be finished explicitly");
        }

        timeRange.finish(endTime);
        endReason = reason;
        terminatedAt = endTime;
    }

    public void markFinished(EventEndReason reason) {
        Objects.requireNonNull(reason, "reason cannot be null");

        if (reason == EventEndReason.CANCELLED) {
            throw new IllegalArgumentException("use cancel() for cancelled events");
        }
        ensureNotTerminated();

        Instant endTime = timeRange.getEnd().orElseThrow(
                () -> new IllegalStateException("an open event must be finished with an end time")
        );
        endReason = reason;
        terminatedAt = endTime;
    }

    public void cancel(Instant cancelledAt) {
        Objects.requireNonNull(cancelledAt, "cancelledAt cannot be null");
        ensureNotTerminated();

        Optional<Instant> plannedEnd = timeRange.getEnd();
        if (plannedEnd.isPresent() && !cancelledAt.isBefore(plannedEnd.get())) {
            throw new IllegalArgumentException("an event cannot be cancelled at or after its end");
        }

        if (cancelledAt.isAfter(getStartTime()) && timeRange.isOpenEnded()) {
            timeRange.finish(cancelledAt);
        }

        endReason = EventEndReason.CANCELLED;
        terminatedAt = cancelledAt;
    }

    private Optional<Instant> effectiveEndTime() {
        if (terminatedAt != null) {
            return Optional.of(terminatedAt);
        }
        return timeRange.getEnd();
    }

    private boolean overlaps(
            Instant otherStart,
            Optional<Instant> otherEnd
    ) {
        if (!hasPositiveEffectiveDuration()) {
            return false;
        }

        Optional<Instant> thisEnd = effectiveEndTime();
        boolean startsBeforeOtherEnds = otherEnd.isEmpty()
                || getStartTime().isBefore(otherEnd.get());
        boolean otherStartsBeforeThisEnds = thisEnd.isEmpty()
                || otherStart.isBefore(thisEnd.get());
        return startsBeforeOtherEnds && otherStartsBeforeThisEnds;
    }

    private boolean hasPositiveEffectiveDuration() {
        return effectiveEndTime()
                .map(end -> end.isAfter(getStartTime()))
                .orElse(true);
    }

    private void ensureNotTerminated() {
        if (endReason != null) {
            throw new IllegalStateException("event has already terminated");
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
