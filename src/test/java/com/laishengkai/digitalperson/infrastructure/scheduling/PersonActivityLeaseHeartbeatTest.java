package com.laishengkai.digitalperson.infrastructure.scheduling;

import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonActivityLeaseHeartbeatTest {
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void periodicallyExtendsAnOwnedLease() {
        PersonActivityScheduleRepository repository = mock(
                PersonActivityScheduleRepository.class
        );
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        PersonActivityScheduleLease lease = lease();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        doReturn(future).when(taskScheduler).scheduleAtFixedRate(
                task.capture(),
                eq(NOW.plus(Duration.ofMinutes(2))),
                eq(Duration.ofMinutes(2))
        );
        when(repository.renewLease(
                lease,
                NOW.plus(Duration.ofMinutes(10)),
                NOW
        )).thenReturn(true);

        PersonActivityLeaseHeartbeat heartbeat = new PersonActivityLeaseHeartbeat(
                repository,
                properties(),
                CLOCK,
                taskScheduler
        );
        PersonActivityLeaseHeartbeat.LeaseHandle handle = heartbeat.start(lease);
        task.getValue().run();

        verify(repository).renewLease(
                lease,
                NOW.plus(Duration.ofMinutes(10)),
                NOW
        );
        assertTrue(handle.leaseOwned());
        handle.close();
        verify(future).cancel(false);
    }

    @Test
    void marksTheHandleLostWhenTheTokenNoLongerOwnsTheLease() {
        PersonActivityScheduleRepository repository = mock(
                PersonActivityScheduleRepository.class
        );
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        PersonActivityScheduleLease lease = lease();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        doReturn(future).when(taskScheduler).scheduleAtFixedRate(
                task.capture(),
                any(Instant.class),
                eq(Duration.ofMinutes(2))
        );
        when(repository.renewLease(any(), any(), any())).thenReturn(false);

        PersonActivityLeaseHeartbeat heartbeat = new PersonActivityLeaseHeartbeat(
                repository,
                properties(),
                CLOCK,
                taskScheduler
        );
        PersonActivityLeaseHeartbeat.LeaseHandle handle = heartbeat.start(lease);
        task.getValue().run();

        assertFalse(handle.leaseOwned());
        handle.close();
    }

    private static PersonActivityScheduleLease lease() {
        return new PersonActivityScheduleLease(
                PersonId.random(),
                UUID.randomUUID(),
                0,
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
                Duration.ofMinutes(2),
                Duration.ofMinutes(8),
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                10,
                4
        );
    }
}
