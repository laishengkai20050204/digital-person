package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.PersonEvent;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronously evaluates one newly active event.
 *
 * <p>An LLM implementation belongs outside {@link StateUpdater}. The updater
 * remains deterministic and never waits on network I/O.</p>
 */
@FunctionalInterface
public interface StateTransitionEvaluator {
    CompletionStage<List<StateTransition>> evaluate(
            PersonStateSnapshot currentState,
            PersonEvent newEvent
    );
}
