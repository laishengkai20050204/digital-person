package com.laishengkai.digitalperson.state;

import java.util.concurrent.CompletionStage;

/** Evaluates both activity-bound transitions and an optional independent aftermath. */
@FunctionalInterface
public interface EventStateImpactEvaluator {
    CompletionStage<EventStateImpact> evaluate(StateEvaluationContext context);
}
