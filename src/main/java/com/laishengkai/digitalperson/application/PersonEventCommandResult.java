package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.util.Objects;

/** Immutable result of one person-event command committed with its state update. */
public record PersonEventCommandResult(
        PersonId personId,
        PersonEvent event,
        PersonStateSnapshot state,
        StateEvolutionContext stateEvolutionContext
) {
    public PersonEventCommandResult {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        event = Objects.requireNonNull(event, "event cannot be null").copy();
        state = Objects.requireNonNull(state, "state cannot be null");
        stateEvolutionContext = Objects.requireNonNull(
                stateEvolutionContext,
                "stateEvolutionContext cannot be null"
        );
    }

    @Override
    public PersonEvent event() {
        return event.copy();
    }
}
