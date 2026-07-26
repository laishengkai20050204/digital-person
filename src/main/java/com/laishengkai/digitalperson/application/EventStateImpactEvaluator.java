package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.state.EventStateImpact;

import java.util.concurrent.CompletionStage;

/**
 * Evaluates the independent state effects directly caused by one event.
 * A valid evaluation may return an empty effect collection.
 */
@FunctionalInterface
public interface EventStateImpactEvaluator {
    CompletionStage<EventStateImpact> evaluate(StateEvaluationContext context);
}
