package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.laishengkai.digitalperson.infrastructure.scheduling.ActivitySchedulerProperties;
import com.laishengkai.digitalperson.infrastructure.scheduling.PersonActivityScheduleRepository;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonCreationRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * MySQL creation boundary that writes a new aggregate and its first scheduler row
 * in the same database transaction.
 */
public final class ScheduledPersonCreationRepository implements PersonCreationRepository {
    private final PersonCreationRepository delegate;
    private final PersonActivityScheduleRepository scheduleRepository;
    private final ActivitySchedulerProperties schedulerProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public ScheduledPersonCreationRepository(
            PersonCreationRepository delegate,
            PersonActivityScheduleRepository scheduleRepository,
            ActivitySchedulerProperties schedulerProperties,
            Clock clock,
            TransactionTemplate transactionTemplate
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.scheduleRepository = Objects.requireNonNull(
                scheduleRepository,
                "scheduleRepository cannot be null"
        );
        this.schedulerProperties = Objects.requireNonNull(
                schedulerProperties,
                "schedulerProperties cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.transactionTemplate = Objects.requireNonNull(
                transactionTemplate,
                "transactionTemplate cannot be null"
        );
    }

    @Override
    public boolean insert(Person person) {
        Person source = Objects.requireNonNull(person, "person cannot be null");
        Boolean inserted = transactionTemplate.execute(status -> {
            if (!delegate.insert(source)) {
                return false;
            }
            Instant firstReviewAt = clock.instant().plus(
                    schedulerProperties.initialReviewDelay()
            );
            scheduleRepository.ensureScheduled(source.getId(), firstReviewAt);
            return true;
        });
        return Boolean.TRUE.equals(inserted);
    }
}
