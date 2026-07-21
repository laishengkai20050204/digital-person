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

    public Optional<PersonEvent> getNext(LocalDateTime time) {
        Objects.requireNonNull(time, "time cannot be null");

        return events.stream()
                .filter(event -> event.getStartTime().isAfter(time))
                .findFirst();
    }
}
