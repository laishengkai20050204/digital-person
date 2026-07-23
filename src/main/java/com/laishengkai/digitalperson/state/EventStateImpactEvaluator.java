package com.laishengkai.digitalperson.state;

import java.util.concurrent.CompletionStage;

/** Evaluates the independent state effects directly caused by one event. */
@FunctionalInterface
public interface EventStateImpactEvaluator {
    CompletionStage<EventStateImpact> evaluate(StateEvaluationContext context);
}
