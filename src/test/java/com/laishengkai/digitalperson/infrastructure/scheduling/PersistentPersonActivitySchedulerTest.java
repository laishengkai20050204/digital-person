package com.laishengkai.digitalperson.infrastructure.scheduling;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.application.PersonActivityDecisionResult;
import com.laishengkai.digitalperson.application.PersonActivityDecisionService;
import com.laishengkai.digitalperson.application.PersonVersionConflictException;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentPersonActivitySchedulerTest {
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void initializesClaimsAndPersistsTheNextSuccessfulReview() {
        PersonActivityScheduleRepository repository = mock(
                PersonActivityScheduleRepository.class
        );
        PersonActivityDecisionService service = mock(PersonActivityDecisionService.class);
        PersonId personId = PersonId.random();
        PersonActivityScheduleLease lease = lease(personId, 0);
        PersonActivityDecisionResult result = mock(PersonActivityDecisionResult.class);
        PersonActivityDecisionPlan plan = mock(PersonActivityDecisionPlan.class);

        when(repository.claimDue(
                NOW,
                4,
                Duration.ofMinutes(10)
        )).thenReturn(List.of(lease));
        when(service.decide(personId, NOW)).thenReturn(
                CompletableFuture.completedFuture(result)
        );
        when(result.nextReviewAt()).thenReturn(NOW.plus(Duration.ofMinutes(15)));
        when(result.plan()).thenReturn(plan);
        when(plan.commands()).thenReturn(List.of());
        when(repository.completeSuccess(
                lease,
                NOW.plus(Duration.ofMinutes(15)),
                NOW
        )).thenReturn(true);

        PersistentPersonActivityScheduler scheduler = new PersistentPersonActivityScheduler(
                repository,
                service,
                properties(),
                CLOCK
        );
        scheduler.poll();

        verify(repository).initializeMissing(NOW.plus(Duration.ofMinutes(1)));
        verify(service).decide(personId, NOW);
        verify(repository).completeSuccess(
                lease,
                NOW.plus(Duration.ofMinutes(15)),
                NOW
        );
        assertEquals(0, scheduler.inFlightCount());
    }

    @Test
    void optimisticConflictIsRescheduledWithoutIncreasingFailureCount() {
        PersonActivityScheduleRepository repository = mock(
                PersonActivityScheduleRepository.class
        );
        PersonActivityDecisionService service = mock(PersonActivityDecisionService.class);
        PersonId personId = PersonId.random();
        PersonActivityScheduleLease lease = lease(personId, 3);

        when(repository.claimDue(
                NOW,
                4,
                Duration.ofMinutes(10)
        )).thenReturn(List.of(lease));
        when(service.decide(personId, NOW)).thenReturn(
                CompletableFuture.failedFuture(
                        new PersonVersionConflictException(personId, 4)
                )
        );

        PersistentPersonActivityScheduler scheduler = new PersistentPersonActivityScheduler(
                repository,
                service,
                properties(),
                CLOCK
        );
        scheduler.poll();

        verify(repository).rescheduleWithoutFailure(
                lease,
                NOW.plusSeconds(30),
                NOW
        );
        verify(repository, never()).completeFailure(
                lease,
                NOW.plus(Duration.ofMinutes(40)),
                "PersonVersionConflictException",
                NOW
        );
        assertEquals(0, scheduler.inFlightCount());
    }

    @Test
    void repeatedModelFailuresUseBoundedExponentialBackoff() {
        PersonActivityScheduleRepository repository = mock(
                PersonActivityScheduleRepository.class
        );
        PersonActivityDecisionService service = mock(PersonActivityDecisionService.class);
        PersonId personId = PersonId.random();
        PersonActivityScheduleLease lease = lease(personId, 3);

        when(repository.claimDue(
                NOW,
                4,
                Duration.ofMinutes(10)
        )).thenReturn(List.of(lease));
        when(service.decide(personId, NOW)).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("provider unavailable"))
        );

        PersistentPersonActivityScheduler scheduler = new PersistentPersonActivityScheduler(
                repository,
                service,
                properties(),
                CLOCK
        );
        scheduler.poll();

        verify(repository).completeFailure(
                lease,
                NOW.plus(Duration.ofMinutes(40)),
                "IllegalStateException",
                NOW
        );
        assertEquals(0, scheduler.inFlightCount());
    }

    private static PersonActivityScheduleLease lease(PersonId personId, int failureCount) {
        return new PersonActivityScheduleLease(
                personId,
                UUID.randomUUID(),
                failureCount,
                NOW,
                NOW.plus(Duration.ofMinutes(10))
        );
    }

    private static ActivitySchedulerProperties properties() {
        return new ActivitySchedulerProperties(
                true,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                10,
                4
        );
    }
}