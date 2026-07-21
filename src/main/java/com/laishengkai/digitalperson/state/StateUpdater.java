package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.PersonEvent;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates state evaluation and mathematical state updates.
 *
 * <p>This class contains no activity-specific coefficients. The evaluator decides
 * which dimensions change and supplies their signed shapes; the transition model
 * performs the deterministic calculation.</p>
 */
public final class StateUpdater {

    private final StateTransitionEvaluator evaluator;
    private final StateTransitionModel transitionModel;

    public StateUpdater(StateTransitionEvaluator evaluator) {
        this(evaluator, new StateTransitionModel());
    }

    public StateUpdater(
            StateTransitionEvaluator evaluator,
            StateTransitionModel transitionModel
    ) {
        this.evaluator = Objects.requireNonNull(
                evaluator,
                "evaluator cannot be null"
        );
        this.transitionModel = Objects.requireNonNull(
                transitionModel,
                "transitionModel cannot be null"
        );
    }

    public void update(
            PersonState state,
            List<PersonEvent> recentEvents,
            Duration elapsed
    ) {
        PersonState currentState = Objects.requireNonNull(
                state,
                "state cannot be null"
        );
        List<PersonEvent> events = List.copyOf(
                Objects.requireNonNull(
                        recentEvents,
                        "recentEvents cannot be null"
                )
        );
        Duration elapsedTime = Objects.requireNonNull(
                elapsed,
                "elapsed cannot be null"
        );

        if (elapsedTime.isNegative()) {
            throw new IllegalArgumentException("elapsed cannot be negative");
        }
        if (elapsedTime.isZero()) {
            return;
        }

        List<StateTransition> transitions = List.copyOf(
                Objects.requireNonNull(
                        evaluator.evaluate(currentState, events),
                        "evaluator result cannot be null"
                )
        );

        transitionModel.applyAll(
                currentState,
                transitions,
                elapsedTime
        );
    }
}
