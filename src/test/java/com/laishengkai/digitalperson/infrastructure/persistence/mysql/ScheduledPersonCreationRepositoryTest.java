package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.laishengkai.digitalperson.infrastructure.scheduling.ActivitySchedulerProperties;
import com.laishengkai.digitalperson.infrastructure.scheduling.PersonActivityScheduleRepository;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonCreationRepository;
import com.laishengkai.digitalperson.personality.Personality;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledPersonCreationRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    @Test
    void insertsAggregateAndFirstScheduleInsideOneTransactionCallback() {
        PersonCreationRepository delegate = mock(PersonCreationRepository.class);
        PersonActivityScheduleRepository schedules = mock(
                PersonActivityScheduleRepository.class
        );
        TransactionTemplate transactions = immediateTransactions();
        Person person = new Person(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        when(delegate.insert(person)).thenReturn(true);
        when(schedules.ensureScheduled(
                person.getId(),
                NOW.plus(Duration.ofMinutes(1))
        )).thenReturn(true);

        ScheduledPersonCreationRepository repository = new ScheduledPersonCreationRepository(
                delegate,
                schedules,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactions
        );

        assertTrue(repository.insert(person));
        verify(schedules).ensureScheduled(
                person.getId(),
                NOW.plus(Duration.ofMinutes(1))
        );
    }

    @Test
    void duplicateAggregateDoesNotAttemptScheduleProvisioning() {
        PersonCreationRepository delegate = mock(PersonCreationRepository.class);
        PersonActivityScheduleRepository schedules = mock(
                PersonActivityScheduleRepository.class
        );
        Person person = new Person(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        when(delegate.insert(person)).thenReturn(false);

        ScheduledPersonCreationRepository repository = new ScheduledPersonCreationRepository(
                delegate,
                schedules,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                immediateTransactions()
        );

        assertFalse(repository.insert(person));
        verify(schedules, never()).ensureScheduled(any(), any());
    }

    @SuppressWarnings("unchecked")
    private static TransactionTemplate immediateTransactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Boolean> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return template;
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
