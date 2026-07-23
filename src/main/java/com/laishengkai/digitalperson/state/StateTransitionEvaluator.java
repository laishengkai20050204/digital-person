package com.laishengkai.digitalperson.state;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Compatibility boundary for evaluators that only provide activity-bound
 * transitions. New production evaluators should implement
 * {@link EventStateImpactEvaluator} when they can also model aftermath.
 */
@FunctionalInterface
public interface StateTransitionEvaluator {
    CompletionStage<List<StateTransition>> evaluate(StateEvaluationContext context);
}
