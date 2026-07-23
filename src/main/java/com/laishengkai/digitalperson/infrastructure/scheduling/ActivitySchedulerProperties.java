package com.laishengkai.digitalperson.infrastructure.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** External configuration for persistent autonomous activity scheduling. */
@ConfigurationProperties(prefix = "digital-person.activity-scheduler")
public record ActivitySchedulerProperties(
        boolean enabled,
        Duration pollInterval,
        Duration initialDelay,
        Duration initialReviewDelay,
        Duration minimumReviewDelay,
        Duration conflictRetryDelay,
        Duration leaseDuration,
        Duration failureBackoff,
        Duration maxFailureBackoff,
        int batchSize,
        int maxInFlight
) {
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(10);
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(30);
    private static final Duration DEFAULT_INITIAL_REVIEW_DELAY = Duration.ofMinutes(1);
    private static final Duration DEFAULT_MINIMUM_REVIEW_DELAY = Duration.ofMinutes(1);
    private static final Duration DEFAULT_CONFLICT_RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(10);
    private static final Duration DEFAULT_FAILURE_BACKOFF = Duration.ofMinutes(5);
    private static final Duration DEFAULT_MAX_FAILURE_BACKOFF = Duration.ofHours(1);
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int DEFAULT_MAX_IN_FLIGHT = 4;

    public ActivitySchedulerProperties {
        pollInterval = defaultIfNull(pollInterval, DEFAULT_POLL_INTERVAL);
        initialDelay = defaultIfNull(initialDelay, DEFAULT_INITIAL_DELAY);
        initialReviewDelay = defaultIfNull(initialReviewDelay, DEFAULT_INITIAL_REVIEW_DELAY);
        minimumReviewDelay = defaultIfNull(minimumReviewDelay, DEFAULT_MINIMUM_REVIEW_DELAY);
        conflictRetryDelay = defaultIfNull(conflictRetryDelay, DEFAULT_CONFLICT_RETRY_DELAY);
        leaseDuration = defaultIfNull(leaseDuration, DEFAULT_LEASE_DURATION);
        failureBackoff = defaultIfNull(failureBackoff, DEFAULT_FAILURE_BACKOFF);
        maxFailureBackoff = defaultIfNull(maxFailureBackoff, DEFAULT_MAX_FAILURE_BACKOFF);
        batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;
        maxInFlight = maxInFlight == 0 ? DEFAULT_MAX_IN_FLIGHT : maxInFlight;

        requirePositive(pollInterval, "pollInterval");
        requireNonNegative(initialDelay, "initialDelay");
        requirePositive(initialReviewDelay, "initialReviewDelay");
        requirePositive(minimumReviewDelay, "minimumReviewDelay");
        requirePositive(conflictRetryDelay, "conflictRetryDelay");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(failureBackoff, "failureBackoff");
        requirePositive(maxFailureBackoff, "maxFailureBackoff");
        if (maxFailureBackoff.compareTo(failureBackoff) < 0) {
            throw new IllegalArgumentException(
                    "maxFailureBackoff cannot be shorter than failureBackoff"
            );
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
    }

    private static Duration defaultIfNull(Duration value, Duration defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }
}