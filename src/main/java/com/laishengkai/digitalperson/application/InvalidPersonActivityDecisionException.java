package com.laishengkai.digitalperson.application;

/** Raised when a model plan is structurally valid but conflicts with current person events. */
public final class InvalidPersonActivityDecisionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidPersonActivityDecisionException(String message) {
        super(message);
    }

    public InvalidPersonActivityDecisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
