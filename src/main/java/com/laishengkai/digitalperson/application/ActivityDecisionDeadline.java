package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.async.DeadlineGuard;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Absolute deadline and phase checkpoint for one activity decision. */
final class ActivityDecisionDeadline {
    private final Instant value;
    private final Clock clock;

    private ActivityDecisionDeadline(Instant value, Clock clock) {
        this.value = value;
        this.clock = clock;
    }

    static ActivityDecisionDeadline require(
            Instant deadline,
            Instant decisionTime,
            Clock clock
    ) {
        Instant value = Objects.requireNonNull(deadline, "deadline cannot be null");
        Instant now = Objects.requireNonNull(
                decisionTime,
                "decisionTime cannot be null"
        );
        if (!value.isAfter(now)) {
            throw new IllegalArgumentException("deadline must be after decisionTime");
        }
        return new ActivityDecisionDeadline(
                value,
                Objects.requireNonNull(clock, "clock cannot be null")
        );
    }

    Instant value() {
        return value;
    }

    void checkpoint(String phase) {
        if (!clock.instant().isBefore(value)) {
            throw new PersonActivityDecisionDeadlineExceededException(value, phase);
        }
    }

    <T> CompletionStage<T> guard(CompletionStage<T> stage, String phase) {
        return DeadlineGuard.before(
                stage,
                value,
                clock,
                () -> new PersonActivityDecisionDeadlineExceededException(
                        value,
                        phase
                )
        );
    }
}
