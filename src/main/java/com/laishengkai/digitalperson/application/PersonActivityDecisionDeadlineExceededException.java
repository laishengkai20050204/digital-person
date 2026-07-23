package com.laishengkai.digitalperson.application;

import java.time.Instant;
import java.util.Objects;

/** Raised when a scheduled activity cycle reaches its overall deadline before commit. */
public final class PersonActivityDecisionDeadlineExceededException extends RuntimeException {
    private final Instant deadline;
    private final String phase;

    public PersonActivityDecisionDeadlineExceededException(
            Instant deadline,
            String phase
    ) {
        super("activity decision deadline exceeded during "
                + requirePhase(phase)
                + ": deadline="
                + Objects.requireNonNull(deadline, "deadline cannot be null"));
        this.deadline = deadline;
        this.phase = phase.strip();
    }

    public Instant deadline() {
        return deadline;
    }

    public String phase() {
        return phase;
    }

    private static String requirePhase(String value) {
        String normalized = Objects.requireNonNull(value, "phase cannot be null").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("phase cannot be blank");
        }
        return normalized;
    }
}
