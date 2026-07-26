package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateUpdatePreparation;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Builds a read-only, current-time state projection without mutating persistence. */
public final class PersonCurrentStateProjector {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            PersonCurrentStateProjector.class
    );

    private final StateUpdater stateUpdater;

    public PersonCurrentStateProjector(StateUpdater stateUpdater) {
        this.stateUpdater = Objects.requireNonNull(
                stateUpdater,
                "stateUpdater cannot be null"
        );
    }

    public Projection project(Person person, Instant projectionTime) {
        Person source = Objects.requireNonNull(person, "person cannot be null");
        Instant now = Objects.requireNonNull(
                projectionTime,
                "projectionTime cannot be null"
        );
        PersonState workingState = source.getState();
        StateUpdatePreparation preparation = stateUpdater.prepareWithNaturalEvolution(
                source.getId().toString(),
                source.getIdentity().timeZone(),
                workingState,
                source.getCurrentPersonEvents(now),
                source.getPersonTimeline().getAll(),
                now,
                source.getStateEvolutionContext(),
                eventEndTimes(source)
        );
        if (!preparation.eventsToEvaluate().isEmpty()) {
            LOGGER.debug(
                    "Current-state projection omitted unevaluated event effects: personId={}, pendingChannels={}",
                    source.getId().toString(),
                    preparation.pendingEvents().keySet()
            );
        }
        return new Projection(workingState.snapshot(), preparation.settledContext());
    }

    private static Map<EventId, Instant> eventEndTimes(Person person) {
        Map<EventId, Instant> endTimes = new HashMap<>();
        for (PersonEvent event : person.getPersonTimeline().getAll()) {
            event.getEndTime().ifPresent(endTime -> endTimes.put(event.getId(), endTime));
        }
        return Map.copyOf(endTimes);
    }

    public record Projection(
            PersonStateSnapshot state,
            StateEvolutionContext evolutionContext
    ) {
        public Projection {
            state = Objects.requireNonNull(state, "state cannot be null");
            evolutionContext = Objects.requireNonNull(
                    evolutionContext,
                    "evolutionContext cannot be null"
            );
        }
    }
}
