package com.laishengkai.digitalperson.infrastructure.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Persistent queue and lease boundary for autonomous activity decisions. */
public interface PersonActivityScheduleRepository {

    /** Creates a first schedule for every persisted person that has no schedule row yet. */
    int initializeMissing(Instant firstReviewAt);

    /** Atomically claims up to {@code limit} due persons for one scheduler process. */
    List<PersonActivityScheduleLease> claimDue(
            Instant now,
            int limit,
            Duration leaseDuration
    );

    /** Completes a claimed cycle and stores the model-recommended next review time. */
    boolean completeSuccess(
            PersonActivityScheduleLease lease,
            Instant nextReviewAt,
            Instant completedAt
    );

    /** Completes a claimed cycle with failure, increments failure count and stores retry time. */
    boolean completeFailure(
            PersonActivityScheduleLease lease,
            Instant retryAt,
            String errorType,
            Instant completedAt
    );

    /** Releases a claim without counting a failure, normally after an optimistic-lock conflict. */
    boolean rescheduleWithoutFailure(
            PersonActivityScheduleLease lease,
            Instant retryAt,
            Instant completedAt
    );
}