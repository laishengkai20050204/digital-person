package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.util.Objects;

public record StateUpdateResult(
        PersonId personId,
        PersonStateSnapshot state,
        StateEvolutionContext context
) {
    public StateUpdateResult {
        Objects.requireNonNull(personId, "personId cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
    }
}
