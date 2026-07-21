package com.laishengkai.digitalperson.experience;

import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Maintains a person's events and enforces timeline invariants.
 */
@ToString
public final class EventTimeline {

    private final List<PersonEvent> events = new ArrayList<>();

    /**
     * Adds a future or planned event without changing existing events.
     */
    public void schedule(PersonEvent event) {
        PersonEvent plannedEvent = requireNewEvent(event);
        if (plannedEvent.isTerminated()) {
            throw new IllegalArgumentException("a terminated event cannot be scheduled");
        }
        ensureNoConflicts(plannedEvent, List.of());
        addInternal(plannedEvent);
    }

    /**
     * Starts an open event. Earlier open events in the same channel are ended
     * with {@link EventEndReason#REPLACED} at the new event's start time.
     */
    public void start(PersonEvent event) {
        PersonEvent newEvent = requireNewEvent(event);
        if (!newEvent.isOpen()) {
            throw new IllegalArgumentException("a started event must be open-ended");
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
     * Adds a closed historical event and marks it completed.
     */
    public void record(PersonEvent event) {
        record(event, EventEndReason.COMPLETED);
    }

    /**
     * Adds a closed historical event with an explicit end reason.
     */
    public void record(PersonEvent event, EventEndReason reason) {
        PersonEvent recordedEvent = requireNewEvent(event);
        Objects.requireNonNull(reason, "reason cannot be null");

        if (recordedEvent.getEndTime().isEmpty()) {
            throw new IllegalArgumentException("a recorded event must have an end time");
        }
        if (reason == EventEndReason.CANCELLED) {
            throw new IllegalArgumentException("a cancelled event must be added through schedule() and cancel()");
        }
        if (recordedEvent.isTerminated()) {
            throw new IllegalArgumentException("a recorded event must not already be terminated");
        }

        ensureNoConflicts(recordedEvent, List.of());
        recordedEvent.markFinished(reason);
        addInternal(recordedEvent);
    }

    public void finish(
            EventId eventId,
            Instant endTime,
            EventEndReason reason
    ) {
        PersonEvent event = getRequired(eventId);
        event.finish(endTime, reason);
    }

    public void cancel(EventId eventId, Instant cancelledAt) {
        getRequired(eventId).cancel(cancelledAt);
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

    public Optional<PersonEvent> getNext(Instant time) {
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
