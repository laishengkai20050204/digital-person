package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.laishengkai.digitalperson.infrastructure.scheduling.PersonActivityScheduleLease;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.personality.Personality;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPersonActivityScheduleRepositoryMySqlTest {
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("digital_person")
            .withUsername("digital_person")
            .withPassword("digital_person_test");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcPersonRepository personRepository;
    private static JdbcPersonActivityScheduleRepository scheduleRepository;

    @BeforeAll
    static void startPersistenceAdapters() {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(MYSQL.getJdbcUrl());
        hikari.setUsername(MYSQL.getUsername());
        hikari.setPassword(MYSQL.getPassword());
        hikari.setMaximumPoolSize(4);
        hikari.setPoolName("activity-schedule-test");
        dataSource = new HikariDataSource(hikari);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/mysql")
                .validateMigrationNaming(true)
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        personRepository = new JdbcPersonRepository(
                jdbcTemplate,
                new PersonAggregateJsonMapper(
                        JsonMapper.builder()
                                .addModule(new JavaTimeModule())
                                .build()
                )
        );
        scheduleRepository = new JdbcPersonActivityScheduleRepository(jdbcTemplate);
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void clearRows() {
        jdbcTemplate.update("DELETE FROM digital_person");
    }

    @Test
    void initializesClaimsAndCompletesSchedulesWithExclusiveLeases() {
        Person first = person();
        Person second = person();
        assertTrue(personRepository.insert(first));
        assertTrue(personRepository.insert(second));

        assertEquals(2, scheduleRepository.initializeMissing(NOW));
        assertEquals(0, scheduleRepository.initializeMissing(NOW.plusSeconds(60)));

        List<PersonActivityScheduleLease> claimed = scheduleRepository.claimDue(
                NOW,
                10,
                Duration.ofMinutes(10)
        );
        assertEquals(2, claimed.size());
        assertTrue(scheduleRepository.claimDue(
                NOW,
                10,
                Duration.ofMinutes(10)
        ).isEmpty());

        PersonActivityScheduleLease success = claimed.getFirst();
        PersonActivityScheduleLease failure = claimed.get(1);
        Instant completedAt = NOW.plusSeconds(1);
        assertTrue(scheduleRepository.completeSuccess(
                success,
                NOW.plus(Duration.ofMinutes(30)),
                completedAt
        ));
        assertTrue(scheduleRepository.completeFailure(
                failure,
                NOW.plus(Duration.ofMinutes(5)),
                "LanguageModelException",
                completedAt
        ));

        assertEquals(0, failureCount(success));
        assertEquals(1, failureCount(failure));
        assertNull(leaseToken(success));
        assertNull(leaseToken(failure));

        List<PersonActivityScheduleLease> retry = scheduleRepository.claimDue(
                NOW.plus(Duration.ofMinutes(6)),
                10,
                Duration.ofMinutes(10)
        );
        assertEquals(1, retry.size());
        assertEquals(failure.personId(), retry.getFirst().personId());
    }

    @Test
    void expiredLeaseCanBeRecoveredByANewRepositoryInstance() {
        Person person = person();
        assertTrue(personRepository.insert(person));
        scheduleRepository.initializeMissing(NOW);

        PersonActivityScheduleLease original = scheduleRepository.claimDue(
                NOW,
                1,
                Duration.ofMinutes(10)
        ).getFirst();

        JdbcPersonActivityScheduleRepository restarted =
                new JdbcPersonActivityScheduleRepository(new JdbcTemplate(dataSource));
        assertTrue(restarted.claimDue(
                NOW.plus(Duration.ofMinutes(9)),
                1,
                Duration.ofMinutes(10)
        ).isEmpty());

        PersonActivityScheduleLease recovered = restarted.claimDue(
                NOW.plus(Duration.ofMinutes(11)),
                1,
                Duration.ofMinutes(10)
        ).getFirst();
        assertEquals(original.personId(), recovered.personId());
        assertFalse(original.leaseToken().equals(recovered.leaseToken()));

        PersonActivityScheduleLease stale = new PersonActivityScheduleLease(
                original.personId(),
                UUID.randomUUID(),
                original.failureCount(),
                original.claimedAt(),
                original.leaseUntil()
        );
        assertFalse(restarted.completeSuccess(
                stale,
                NOW.plus(Duration.ofMinutes(30)),
                NOW.plus(Duration.ofMinutes(12))
        ));
    }

    private static int failureCount(PersonActivityScheduleLease lease) {
        return jdbcTemplate.queryForObject(
                "SELECT failure_count FROM person_activity_schedule WHERE person_id = ?",
                Integer.class,
                lease.personId().toString()
        );
    }

    private static String leaseToken(PersonActivityScheduleLease lease) {
        return jdbcTemplate.queryForObject(
                "SELECT lease_token FROM person_activity_schedule WHERE person_id = ?",
                String.class,
                lease.personId().toString()
        );
    }

    private static Person person() {
        return new Person(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
    }
}