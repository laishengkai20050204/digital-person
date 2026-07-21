package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.PersonEvent;

import java.util.List;

/**
 * Evaluates recent events and returns the state transitions that should be applied.
 *
 * <p>The implementation may later call a large language model. The state layer only
 * depends on this interface and does not depend on a specific AI framework.</p>
 */
@FunctionalInterface
public interface StateTransitionEvaluator {

    List<StateTransition> evaluate(
            PersonState currentState,
            List<PersonEvent> recentEvents
    );
}
