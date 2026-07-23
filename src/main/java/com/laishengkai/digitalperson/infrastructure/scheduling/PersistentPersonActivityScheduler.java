package com.laishengkai.digitalperson.infrastructure.scheduling;

import com.laishengkai.digitalperson.application.PersonActivityDecisionResult;
import com.laishengkai.digitalperson.application.PersonActivityDecisionService;
import com.laishengkai.digitalperson.application.PersonVersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Polls the persistent queue and runs due autonomous activity-decision cycles. */
public final class PersistentPersonActivityScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            PersistentPersonActivityScheduler.class
    );

    private final PersonActivityScheduleRepository scheduleRepository;
    private final PersonActivityDecisionService decisionService;
    private final ActivitySchedulerProperties properties;
    private final Clock clock;
    private final AtomicInteger inFlight = new AtomicInteger();

    public PersistentPersonActivityScheduler(
            PersonActivityScheduleRepository scheduleRepository,
            PersonActivityDecisionService decisionService,
            ActivitySchedulerProperties properties,
            Clock clock
    ) {
        this.scheduleRepository = Objects.requireNonNull(
                scheduleRepository,
                "scheduleRepository cannot be null"
        );
        this.decisionService = Objects.requireNonNull(
                decisionService,
                "decisionService cannot be null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @Scheduled(
            fixedDelayString = "${digital-person.activity-scheduler.poll-interval:10s}",
            initialDelayString = "${digital-person.activity-scheduler.initial-delay:30s}"
    )
    public void poll() {
        Instant now = clock.instant();
        try {
            int initialized = scheduleRepository.initializeMissing(
                    now.plus(properties.initialReviewDelay())
            );
            if (initialized > 0) {
                LOGGER.info(
                        "Initialized persistent activity schedules: personCount={}, firstReviewAt={}",
                        initialized,
                        now.plus(properties.initialReviewDelay())
                );
            }

            int capacity = properties.maxInFlight() - inFlight.get();
            if (capacity <= 0) {
                return;
            }
            int claimLimit = Math.min(properties.batchSize(), capacity);
            List<PersonActivityScheduleLease> leases = scheduleRepository.claimDue(
                    now,
                    claimLimit,
                    properties.leaseDuration()
            );
            for (PersonActivityScheduleLease lease : leases) {
                inFlight.incrementAndGet();
                execute(lease);
            }
        } catch (RuntimeException error) {
            LOGGER.error("Persistent activity scheduler poll failed", error);
        }
    }

    int inFlightCount() {
        return inFlight.get();
    }

    private void execute(PersonActivityScheduleLease lease) {
        LOGGER.info(
                "Starting scheduled activity decision: personId={}, failureCount={}, leaseUntil={}",
                lease.personId(),
                lease.failureCount(),
                lease.leaseUntil()
        );
        try {
            decisionService.decide(lease.personId(), lease.claimedAt())
                    .whenComplete((result, error) -> complete(lease, result, error));
        } catch (RuntimeException error) {
            complete(lease, null, error);
        }
    }

    private void complete(
            PersonActivityScheduleLease lease,
            PersonActivityDecisionResult result,
            Throwable error
    ) {
        Instant completedAt = clock.instant();
        try {
            if (error == null) {
                completeSuccess(lease, result, completedAt);
                return;
            }
            completeFailure(lease, unwrap(error), completedAt);
        } catch (RuntimeException persistenceFailure) {
            LOGGER.error(
                    "Could not persist scheduled activity result: personId={}, leaseToken={}",
                    lease.personId(),
                    lease.leaseToken(),
                    persistenceFailure
            );
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void completeSuccess(
            PersonActivityScheduleLease lease,
            PersonActivityDecisionResult result,
            Instant completedAt
    ) {
        PersonActivityDecisionResult safeResult = Objects.requireNonNull(
                result,
                "scheduled decision result cannot be null"
        );
        Instant minimumNextReviewAt = completedAt.plus(properties.minimumReviewDelay());
        Instant nextReviewAt = safeResult.nextReviewAt().isAfter(minimumNextReviewAt)
                ? safeResult.nextReviewAt()
                : minimumNextReviewAt;
        boolean completed = scheduleRepository.completeSuccess(
                lease,
                nextReviewAt,
                completedAt
        );
        if (!completed) {
            LOGGER.warn(
                    "Scheduled activity lease was no longer owned at success completion: personId={}, leaseToken={}",
                    lease.personId(),
                    lease.leaseToken()
            );
            return;
        }
        LOGGER.info(
                "Completed scheduled activity decision: personId={}, commandCount={}, nextReviewAt={}",
                lease.personId(),
                safeResult.plan().commands().size(),
                nextReviewAt
        );
    }

    private void completeFailure(
            PersonActivityScheduleLease lease,
            Throwable error,
            Instant completedAt
    ) {
        if (error instanceof PersonVersionConflictException) {
            Instant retryAt = completedAt.plus(properties.conflictRetryDelay());
            boolean rescheduled = scheduleRepository.rescheduleWithoutFailure(
                    lease,
                    retryAt,
                    completedAt
            );
            LOGGER.info(
                    "Rescheduled activity decision after version conflict: personId={}, retryAt={}, leaseOwned={}",
                    lease.personId(),
                    retryAt,
                    rescheduled
            );
            return;
        }

        Duration delay = failureDelay(lease.failureCount());
        Instant retryAt = completedAt.plus(delay);
        String errorType = safeErrorType(error);
        boolean completed = scheduleRepository.completeFailure(
                lease,
                retryAt,
                errorType,
                completedAt
        );
        LOGGER.warn(
                "Scheduled activity decision failed: personId={}, errorType={}, retryAt={}, leaseOwned={}",
                lease.personId(),
                errorType,
                retryAt,
                completed
        );
    }

    private Duration failureDelay(int previousFailureCount) {
        Duration delay = properties.failureBackoff();
        Duration maximum = properties.maxFailureBackoff();
        for (int attempt = 0; attempt < previousFailureCount; attempt++) {
            if (delay.compareTo(maximum) >= 0) {
                return maximum;
            }
            Duration doubled;
            try {
                doubled = delay.multipliedBy(2);
            } catch (ArithmeticException overflow) {
                return maximum;
            }
            delay = doubled.compareTo(maximum) > 0 ? maximum : doubled;
        }
        return delay.compareTo(maximum) > 0 ? maximum : delay;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = Objects.requireNonNull(error, "error cannot be null");
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeErrorType(Throwable error) {
        String simpleName = Objects.requireNonNull(error, "error cannot be null")
                .getClass()
                .getSimpleName();
        return simpleName.isBlank() ? "UnknownFailure" : simpleName;
    }
}