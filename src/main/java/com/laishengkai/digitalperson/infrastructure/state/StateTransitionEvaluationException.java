package com.laishengkai.digitalperson.infrastructure.state;

/** Indicates that a model response could not be accepted as state transitions. */
public final class StateTransitionEvaluationException extends RuntimeException {

    public StateTransitionEvaluationException(String message) {
        super(message);
    }

    public StateTransitionEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
