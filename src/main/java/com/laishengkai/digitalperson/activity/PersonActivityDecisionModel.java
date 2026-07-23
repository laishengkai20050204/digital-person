package com.laishengkai.digitalperson.activity;

import java.util.concurrent.CompletionStage;

/** Provider-neutral boundary that proposes digital-person event lifecycle changes. */
@FunctionalInterface
public interface PersonActivityDecisionModel {
    CompletionStage<PersonActivityDecisionPlan> decide(
            PersonActivityDecisionContext context
    );
}
