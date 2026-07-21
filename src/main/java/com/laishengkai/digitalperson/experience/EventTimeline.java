package com.laishengkai.digitalperson.experience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Maintains events that actually happened or are currently happening.
 *
 * <p>The aggregate stores defensive copies and returns defensive copies. All
 * event lifecycle changes pass through this class so channel conflicts, event
 * ordering and finish rules cannot be bypassed.</p>
 *
 * <p>Logs contain event identifiers and structural metadata only. Human-readable
 * titles, locations, participants and notes are intentionally excluded because
 * they may contain private user information.</p>
 */
public final class EventTimeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventTimeline.class);

    private final List<PersonEvent> events;

    public EventTimeline() {
        this.events = new ArrayList<>();
    }

    private EventTimeline(List<PersonEvent> events) {
        this.events = events.stream()
                .map(PersonEvent::copy)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /** Returns a fully detached timeline copy. */
    public EventTimeline copy() {
        return new EventTimeline(events);
    }

    /**
     * Starts an open event and replaces an older open event in the same channel.
     *
     * <p>Events in different channels may remain active concurrently.</p>
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
        sameChannelOpenEvents.forEach(existing -> {
            existing.finish(newEvent.getStartTime(), EventEndReason.REPLACED);
            LOGGER.debug(
                    "Replaced open event: eventId={}, replacementEventId={}, channel={}, endTime={}",
                    existing.getId(),
                    newEvent.getId(),
                    existing.getChannel(),
                    newEvent.getStartTime()
            );
        });
        addInternal(newEvent);

        LOGGER.debug(
                "Started event: eventId={}, activityType={}, channel={}, startTime={}",
                newEvent.getId(),
                newEvent.getActivityType(),
                newEvent.getChannel(),
                newEvent.getStartTime()
        );
    }

    /** Records a completed historical event using {@link EventEndReason#COMPLETED}. */
    public void record(PersonEvent event, Instant now) {
        record(event, EventEndReason.COMPLETED, now);
    }

    /**
     * Records an event that already has both start and end times.
     *
     * <p>The supplied event must not already carry an end reason because this
     * aggregate owns the lifecycle transition to the finished state.</p>
     */
    public void record(PersonEvent event, EventEndReason reason, Instant now) {
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

        LOGGER.debug(
                "Recorded event: eventId={}, activityType={}, channel={}, startTime={}, endTime={}, endReason={}",
                recordedEvent.getId(),
                recordedEvent.getActivityType(),
                recordedEvent.getChannel(),
                recordedEvent.getStartTime(),
                endTime,
                reason
        );
    }

    /** Finishes one currently open event at the supplied logical time. */
    public void finish(EventId eventId, Instant endTime, EventEndReason reason, Instant now) {
        Objects.requireNonNull(endTime, "endTime cannot be null");
        Instant registrationTime = Objects.requireNonNull(now, "now cannot be null");
        if (endTime.isAfter(registrationTime)) {
            throw new IllegalArgumentException("an event cannot finish in the future");
        }

        PersonEvent event = getInternalRequired(eventId);
        event.finish(endTime, reason);

        LOGGER.debug(
                "Finished event: eventId={}, activityType={}, channel={}, endTime={}, endReason={}",
                event.getId(),
                event.getActivityType(),
                event.getChannel(),
                endTime,
                reason
        );
    }

    /** Removes an event by identity and reports whether it existed. */
    public boolean remove(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        boolean removed = events.removeIf(event -> event.getId().equals(eventId));
        if (removed) {
            LOGGER.debug("Removed event: eventId={}", eventId);
        }
        return removed;
    }

    public Optional<PersonEvent> getById(EventId eventId) {
        return findInternalById(eventId).map(PersonEvent::copy);
    }

    public List<PersonEvent> getAll() {
        return copyEvents(events);
    }

    public List<PersonEvent> getCurrentEvents(Instant time) {
        Objects.requireNonNull(time, "time cannot be null");
        return events.stream()
                .filter(event -> event.contains(time))
                .map(PersonEvent::copy)
                .toList();
    }

    public Optional<PersonEvent> getCurrentInChannel(ActivityChannel channel, Instant time) {
        Objects.requireNonNull(channel, "channel cannot be null");
        Objects.requireNonNull(time, "time cannot be null");
        return events.stream()
                .filter(event -> event.getChannel() == channel)
                .filter(event -> event.contains(time))
                .findFirst()
                .map(PersonEvent::copy);
    }

    public Optional<PersonEvent> getFirstStartedAfter(Instant time) {
        Objects.requireNonNull(time, "time cannot be null");
        return events.stream()
                .filter(event -> event.getStartTime().isAfter(time))
                .findFirst()
                .map(PersonEvent::copy);
    }

    public List<PersonEvent> getBetween(Instant startTime, Instant endTime) {
        TimeRange queryRange = TimeRange.closed(startTime, endTime);
        return events.stream()
                .filter(event -> event.overlaps(queryRange))
                .map(PersonEvent::copy)
                .toList();
    }

    public List<PersonEvent> getRecentEvents(Instant now, Duration duration) {
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
                .map(PersonEvent::copy)
                .toList();
    }

    public List<PersonEvent> findConflictingEvents(PersonEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        return findOverlappingEvents(event).stream()
                .filter(existing -> existing.getChannel() == event.getChannel())
                .toList();
    }

    /** Creates the aggregate-owned event instance and rejects duplicate identity. */
    private PersonEvent requireNewEvent(PersonEvent event) {
        PersonEvent copy = Objects.requireNonNull(event, "event cannot be null").copy();
        if (findInternalById(copy.getId()).isPresent()) {
            throw new IllegalArgumentException("event id already exists: " + copy.getId());
        }
        return copy;
    }

    /** Enforces that overlapping events cannot occupy the same activity channel. */
    private void ensureNoConflicts(PersonEvent event, List<PersonEvent> allowedConflicts) {
        List<EventId> allowedIds = allowedConflicts.stream().map(PersonEvent::getId).toList();
        List<PersonEvent> conflicts = events.stream()
                .filter(existing -> !existing.getId().equals(event.getId()))
                .filter(existing -> existing.overlaps(event))
                .filter(existing -> existing.getChannel() == event.getChannel())
                .filter(existing -> !allowedIds.contains(existing.getId()))
                .toList();
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "event conflicts with existing events in channel " + event.getChannel()
            );
        }
    }

    private Optional<PersonEvent> findInternalById(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        return events.stream().filter(event -> event.getId().equals(eventId)).findFirst();
    }

    private PersonEvent getInternalRequired(EventId eventId) {
        return findInternalById(eventId).orElseThrow(
                () -> new IllegalArgumentException("event does not exist: " + eventId)
        );
    }

    private void addInternal(PersonEvent event) {
        events.add(event);
        events.sort(Comparator.comparing(PersonEvent::getStartTime));
    }

    private static List<PersonEvent> copyEvents(List<PersonEvent> source) {
        return source.stream().map(PersonEvent::copy).toList();
    }

    @Override
    public String toString() {
        return "EventTimeline[eventCount=" + events.size() + "]";
    }
}
