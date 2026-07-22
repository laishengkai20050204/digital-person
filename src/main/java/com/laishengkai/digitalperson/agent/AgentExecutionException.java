package com.laishengkai.digitalperson.agent;

/** Raised when a bounded agent execution cannot produce a final response. */
public final class AgentExecutionException extends RuntimeException {

    public AgentExecutionException(String message) {
        super(message);
    }

    public AgentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
