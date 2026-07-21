package com.laishengkai.digitalperson.experience;

import lombok.ToString;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Maintains events that actually happened or are currently happening.
 */
@ToString
public final class EventTimeline {

    private final List<PersonEvent> events = new ArrayList<>();

    /**
     * Starts an actual open event. The event must already have begun by
     * {@code now}. Earlier open events in the same channel are ended with
     * {@link EventEndReason#REPLACED} at the new event's start time.
     */
    public void start(PersonEvent event, Instant now) {
        PersonEvent newEvent = requireNewEvent(event);
        Instant registrationTime = Objects.requireNonNull(now, "now cannot be null");

        if (!newEvent.isOpen()) {
            throw new IllegalArgumentException("a started event must be open-ended");
        }
        if (newEvent.getStartTime().isAfter(registrationTime)) {
            throw new IllegalArgumentException("an event cannot start in the future");
        }

        List<PersonEvent> sameChannelOpenEvents = events.stream()
                .filter(PersonEvent::isOpen)
                .filter(existing -> existing.getChannel() == newEvent.getChannel())
                .toList();

        boolean outOfOrderEventExists = sameChannelOpenEvents.stream()
                .anyMatch(existing -> !existing.getStartTime().isBefore(newEvent.getStartTime()));

        if (outOfOrderEventExists) {
            throw new IllegalStateException(
                    "cannot start an event before or at an existing open event in the same channel"
            );
        }

        ensureNoConflicts(newEvent, sameChannelOpenEvents);

        sameChannelOpenEvents.forEach(existing -> existing.finish(
                newEvent.getStartTime(),
                EventEndReason.REPLACED
        ));
        addInternal(newEvent);
    }

    /**
     * Records an actual event whose start and end are already known.
     */
    public void record(PersonEvent event, Instant now) {
        record(event, EventEndReason.COMPLETED, now);
    }

    /**
     * Records an actual event with an explicit end reason. Its end time must
     * not be later than {@code now}.
     */
    public void record(
            PersonEvent event,
            EventEndReason reason,
            Instant now
    ) {
        PersonEvent recordedEvent = requireNewEvent(event);
        Objects.requireNonNull(reason, "reason cannot be null");
        Instant registrationTime = Objects.requireNonNull(now, "now cannot be null");

        Instant endTime = recordedEvent.getEndTime().orElseThrow(
                () -> new IllegalArgumentException("a recorded event must have an end time")
        );
        if (endTime.isAfter(registrationTime)) {
            throw new IllegalArgumentException("a recorded event cannot end in the future");
        }
        if (recordedEvent.isFinished()) {
            throw new IllegalArgumentException("a recorded event must not already be finished");
        }

        ensureNoConflicts(recordedEvent, List.of());
        recordedEvent.markFinished(reason);
        addInternal(recordedEvent);
    }

    /**
     * Finishes an open event. The supplied end time must not be later than
     * {@code now}.
     */
    public void finish(
            EventId eventId,
            Instant endTime,
            EventEndReason reason,
            Instant now
    ) {
        Objects.requireNonNull(endTime, "endTime cannot be null");
        Instant registrationTime = Objects.requireNonNull(now, "now cannot be null");

        if (endTime.isAfter(registrationTime)) {
            throw new IllegalArgumentException("an event cannot finish in the future");
        }

        getRequired(eventId).finish(endTime, reason);
    }

    public boolean remove(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        return events.removeIf(event -> event.getId().equals(eventId));
    }

    public Optional<PersonEvent> getById(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        return events.stream()
                .filter(event -> event.getId().equals(eventId))
                .findFirst();
    }

    public List<PersonEvent> getAll() {
        return List.copyOf(events);
    }

    public List<PersonEvent> getCurrentEvents(Instant time) {
        Objects.requireNonNull(time, "time cannot be null");

        return events.stream()
                .filter(event -> event.contains(time))
                .toList();
    }

    public Optional<PersonEvent> getCurrentInChannel(
            ActivityChannel channel,
            Instant time
    ) {
        Objects.requireNonNull(channel, "channel cannot be null");
        Objects.requireNonNull(time, "time cannot be null");

        return events.stream()
                .filter(event -> event.getChannel() == channel)
                .filter(event -> event.contains(time))
                .findFirst();
    }

    /**
     * Returns the first actual event that started after the supplied time.
     */
    public Optional<PersonEvent> getFirstStartedAfter(Instant time) {
        Objects.requireNonNull(time, "time cannot be null");

        return events.stream()
                .filter(event -> event.getStartTime().isAfter(time))
                .findFirst();
    }

    public List<PersonEvent> getBetween(
            Instant startTime,
            Instant endTime
    ) {
        TimeRange queryRange = TimeRange.closed(startTime, endTime);

        return events.stream()
                .filter(event -> event.overlaps(queryRange))
                .toList();
    }

    /**
     * Returns actual events overlapping {@code [now - duration, now)}.
     */
    public List<PersonEvent> getRecentEvents(
            Instant now,
            Duration duration
    ) {
        Objects.requireNonNull(now, "now cannot be null");
        Objects.requireNonNull(duration, "duration cannot be null");

        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }

        return getBetween(now.minus(duration), now);
    }

    public List<PersonEvent> getLast24Hours(Instant now) {
        return getRecentEvents(now, Duration.ofHours(24));
    }

    public List<PersonEvent> findOverlappingEvents(PersonEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        return events.stream()
                .filter(existing -> !existing.getId().equals(event.getId()))
                .filter(existing -> existing.overlaps(event))
                .toList();
    }

    public List<PersonEvent> findConflictingEvents(PersonEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        return findOverlappingEvents(event).stream()
                .filter(existing -> existing.getChannel() == event.getChannel())
                .toList();
    }

    private PersonEvent requireNewEvent(PersonEvent event) {
        PersonEvent nonNullEvent = Objects.requireNonNull(event, "event cannot be null");
        if (getById(nonNullEvent.getId()).isPresent()) {
            throw new IllegalArgumentException("event id already exists: " + nonNullEvent.getId());
        }
        return nonNullEvent;
    }

    private void ensureNoConflicts(
            PersonEvent event,
            List<PersonEvent> allowedConflicts
    ) {
        List<EventId> allowedIds = allowedConflicts.stream()
                .map(PersonEvent::getId)
                .toList();

        List<PersonEvent> conflicts = findConflictingEvents(event).stream()
                .filter(existing -> !allowedIds.contains(existing.getId()))
                .toList();

        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "event conflicts with existing events in channel " + event.getChannel()
            );
        }
    }

    private PersonEvent getRequired(EventId eventId) {
        return getById(eventId).orElseThrow(
                () -> new IllegalArgumentException("event does not exist: " + eventId)
        );
    }

    private void addInternal(PersonEvent event) {
        events.add(event);
        events.sort(Comparator.comparing(PersonEvent::getStartTime));
    }
}
