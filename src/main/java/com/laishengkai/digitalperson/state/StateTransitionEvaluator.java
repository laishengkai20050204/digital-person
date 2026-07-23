package com.laishengkai.digitalperson.state;

import java.util.concurrent.CompletionStage;

/**
 * Asynchronously evaluates one newly active event using a complete preassembled
 * context. Implementations must not retrieve repositories, memory or chat data
 * themselves.
 */
@FunctionalInterface
public interface StateTransitionEvaluator {
    CompletionStage<EventStateImpact> evaluate(StateEvaluationContext context);
}
