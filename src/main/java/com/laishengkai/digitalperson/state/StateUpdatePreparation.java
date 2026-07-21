package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.PersonEvent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic result of settling existing effects and detecting new events.
 *
 * <p>The pending events can be evaluated asynchronously outside the domain
 * update operation.</p>
 */
public record StateUpdatePreparation(
        StateEvolutionContext settledContext,
        Map<ActivityChannel, PersonEvent> pendingEvents
) {
    public StateUpdatePreparation {
        Objects.requireNonNull(settledContext, "settledContext cannot be null");

        Map<ActivityChannel, PersonEvent> copy =
                new EnumMap<>(ActivityChannel.class);
        Objects.requireNonNull(pendingEvents, "pendingEvents cannot be null")
                .forEach((channel, event) -> {
                    Objects.requireNonNull(channel, "channel cannot be null");
                    PersonEvent nonNullEvent = Objects.requireNonNull(
                            event,
                            "event cannot be null"
                    );
                    if (nonNullEvent.getChannel() != channel) {
                        throw new IllegalArgumentException(
                                "event channel must match map key"
                        );
                    }
                    copy.put(channel, nonNullEvent.copy());
                });
        pendingEvents = Map.copyOf(copy);
    }

    public List<PersonEvent> eventsToEvaluate() {
        return pendingEvents.values().stream().map(PersonEvent::copy).toList();
    }
}
