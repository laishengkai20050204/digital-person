package com.laishengkai.digitalperson.infrastructure.activity;

/** Raised when an activity-decision model returns an invalid lifecycle plan. */
public final class PersonActivityDecisionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PersonActivityDecisionException(String message) {
        super(message);
    }

    public PersonActivityDecisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
