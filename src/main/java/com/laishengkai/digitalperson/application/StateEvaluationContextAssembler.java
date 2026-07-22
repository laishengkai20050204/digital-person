package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvaluationContext;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** Coordinates retrieval and assembly before model-backed state evaluation. */
@FunctionalInterface
public interface StateEvaluationContextAssembler {
    CompletionStage<StateEvaluationContext> assemble(
            Person person,
            PersonStateSnapshot currentState,
            PersonEvent newEvent,
            Instant evaluationTime
    );
}
