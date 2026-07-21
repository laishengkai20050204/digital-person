package com.laishengkai.digitalperson.experience;

import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@ToString
public final class EventTimeline {

    private final List<PersonEvent> events = new ArrayList<>();

    public void add(PersonEvent event) {
        events.add(Objects.requireNonNull(event, "event cannot be null"));
        events.sort(Comparator.comparing(PersonEvent::getStartTime));
    }

    public void start(PersonEvent event) {
        PersonEvent newEvent = Objects.requireNonNull(event, "event cannot be null");

        if (newEvent.getActivityType().isExclusive()) {
            finishOpenExclusiveEvents(newEvent.getStartTime());
        }

        add(newEvent);
    }

    public boolean remove(PersonEvent event) {
        return events.remove(Objects.requireNonNull(event, "event cannot be null"));
    }

    public List<PersonEvent> getAll() {
        return List.copyOf(events);
    }

    public Optional<PersonEvent> getCurrent(LocalDateTime time) {
        Objects.requireNonNull(time, "time cannot be null");

        return events.stream()
                .filter(event -> event.contains(time))
                .findFirst();
    }

    public List<PersonEvent> getCurrentEvents(LocalDateTime time) {
        Objects.requireNonNull(time, "time cannot be null");

        return events.stream()
                .filter(event -> event.contains(time))
                .toList();
    }

    public Optional<PersonEvent> getNext(LocalDateTime time) {
        Objects.requireNonNull(time, "time cannot be null");

        return events.stream()
                .filter(event -> event.getStartTime().isAfter(time))
                .findFirst();
    }

    public List<PersonEvent> getBetween(
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        TimeRange queryRange = TimeRange.closed(startTime, endTime);

        return events.stream()
                .filter(event -> event.getTimeRange().overlaps(queryRange))
                .toList();
    }

    public List<PersonEvent> findConflicts(PersonEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        return events.stream()
                .filter(existing -> existing != event)
                .filter(existing -> existing.overlaps(event))
                .toList();
    }

    private void finishOpenExclusiveEvents(LocalDateTime newStartTime) {
        List<PersonEvent> openExclusiveEvents = events.stream()
                .filter(event -> event.getActivityType().isExclusive())
                .filter(event -> event.getTimeRange().isOpenEnded())
                .filter(event -> event.getStartTime().isBefore(newStartTime))
                .toList();

        boolean sameStartExists = events.stream()
                .filter(event -> event.getActivityType().isExclusive())
                .filter(event -> event.getTimeRange().isOpenEnded())
                .anyMatch(event -> event.getStartTime().isEqual(newStartTime));

        if (sameStartExists) {
            throw new IllegalStateException(
                    "an open exclusive event already starts at " + newStartTime
            );
        }

        openExclusiveEvents.forEach(event -> event.finish(newStartTime));
    }
}
