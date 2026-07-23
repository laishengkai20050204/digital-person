package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionContext;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** Assembles provider-neutral context for one autonomous activity decision. */
@FunctionalInterface
public interface PersonActivityDecisionContextAssembler {
    CompletionStage<PersonActivityDecisionContext> assemble(
            Person person,
            PersonStateSnapshot currentState,
            StateEvolutionContext currentEvolution,
            String observation,
            Instant evaluationTime
    );
}
