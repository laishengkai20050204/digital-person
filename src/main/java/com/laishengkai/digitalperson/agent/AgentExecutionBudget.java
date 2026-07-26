package com.laishengkai.digitalperson.agent;

import java.time.Duration;
import java.util.Objects;

/** Monotonic time budget shared by one complete agent execution. */
final class AgentExecutionBudget {
    private final long deadlineNanos;

    private AgentExecutionBudget(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    static AgentExecutionBudget start(Duration timeout) {
        Duration safeTimeout = requirePositive(timeout, "timeout");
        long startedAtNanos = System.nanoTime();
        try {
            return new AgentExecutionBudget(Math.addExact(
                    startedAtNanos,
                    safeTimeout.toNanos()
            ));
        } catch (ArithmeticException overflow) {
            return new AgentExecutionBudget(Long.MAX_VALUE);
        }
    }

    Duration cap(Duration operationTimeout) {
        Duration safeOperationTimeout = requirePositive(
                operationTimeout,
                "operationTimeout"
        );
        Duration remaining = remaining();
        return safeOperationTimeout.compareTo(remaining) <= 0
                ? safeOperationTimeout
                : remaining;
    }

    private Duration remaining() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new AgentExecutionException("agent execution timed out");
        }
        return Duration.ofNanos(remainingNanos);
    }

    static Duration requirePositive(Duration value, String fieldName) {
        Duration duration = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        );
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        duration.toNanos();
        return duration;
    }
}
