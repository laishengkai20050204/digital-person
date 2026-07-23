package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** Coordinates retrieval and assembly before model-backed state evaluation. */
@FunctionalInterface
public interface StateEvaluationContextAssembler {
    CompletionStage<StateEvaluationContext> assemble(
            Person person,
            PersonStateSnapshot currentState,
            StateEvolutionContext currentEvolution,
            PersonEvent newEvent,
            Instant evaluationTime
    );

    /** Compatibility path using the aggregate's currently persisted evolution context. */
    default CompletionStage<StateEvaluationContext> assemble(
            Person person,
            PersonStateSnapshot currentState,
            PersonEvent newEvent,
            Instant evaluationTime
    ) {
        return assemble(
                person,
                currentState,
                person.getStateEvolutionContext(),
                newEvent,
                evaluationTime
        );
    }
}
