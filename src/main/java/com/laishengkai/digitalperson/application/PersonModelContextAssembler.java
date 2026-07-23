package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.modelcontext.PersonModelContextSnapshot;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** Shared context extraction boundary for every model that reasons about one person. */
@FunctionalInterface
public interface PersonModelContextAssembler {
    CompletionStage<PersonModelContextSnapshot> assemble(
            Person person,
            PersonStateSnapshot currentState,
            StateEvolutionContext currentEvolution,
            PersonModelContextAssemblyRequest request,
            Instant evaluationTime
    );
}
