package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.PersonEvent;

import java.util.List;

/**
 * Evaluates one newly active event and returns that event's channel-specific
 * state transitions.
 *
 * <p>An implementation may later call a large language model. The updater calls
 * this interface only when a different event becomes current in a channel.</p>
 */
@FunctionalInterface
public interface StateTransitionEvaluator {

    List<StateTransition> evaluate(
            PersonState currentState,
            PersonEvent newEvent
    );
}
