package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.laishengkai.digitalperson.infrastructure.scheduling.PersonActivityScheduleLease;
import com.laishengkai.digitalperson.person.PersonId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPersonActivityScheduleLeaseRenewalMySqlTest {
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("digital_person")
            .withUsername("digital_person")
            .withPassword("digital_person_test");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcPersonActivityScheduleRepository repository;

    @BeforeAll
    static void startPersistenceAdapter() {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(MYSQL.getJdbcUrl());
        hikari.setUsername(MYSQL.getUsername());
        hikari.setPassword(MYSQL.getPassword());
        hikari.setMaximumPoolSize(4);
        hikari.setPoolName("activity-lease-renewal-test");
        dataSource = new HikariDataSource(hikari);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/mysql")
                .validateMigrationNaming(true)
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcPersonActivityScheduleRepository(jdbcTemplate);
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void clearRows() {
        jdbcTemplate.update("DELETE FROM person_activity_schedule");
        jdbcTemplate.update("DELETE FROM digital_person");
    }

    @Test
    void onlyTheCurrentUnexpiredTokenCanRenewTheLease() {
        PersonId personId = insertPersonAndSchedule();
        List<PersonActivityScheduleLease> claimed = repository.claimDue(
                NOW,
                1,
                Duration.ofMinutes(10)
        );
        assertEquals(1, claimed.size());
        PersonActivityScheduleLease lease = claimed.getFirst();

        Instant renewedAt = NOW.plus(Duration.ofMinutes(2));
        Instant newLeaseUntil = renewedAt.plus(Duration.ofMinutes(10));
        assertTrue(repository.renewLease(lease, newLeaseUntil, renewedAt));
        assertEquals(
                newLeaseUntil,
                jdbcTemplate.queryForObject(
                        "SELECT lease_until FROM person_activity_schedule WHERE person_id = ?",
                        (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
                        personId.toString()
                )
        );

        PersonActivityScheduleLease staleToken = new PersonActivityScheduleLease(
                lease.personId(),
                java.util.UUID.randomUUID(),
                lease.failureCount(),
                lease.claimedAt(),
                lease.leaseUntil()
        );
        assertFalse(repository.renewLease(
                staleToken,
                renewedAt.plus(Duration.ofMinutes(20)),
                renewedAt.plusSeconds(1)
        ));
    }

    @Test
    void anExpiredLeaseCannotBeRevivedByItsOldToken() {
        insertPersonAndSchedule();
        PersonActivityScheduleLease lease = repository.claimDue(
                NOW,
                1,
                Duration.ofMinutes(10)
        ).getFirst();
        Instant afterExpiry = lease.leaseUntil().plusSeconds(1);

        jdbcTemplate.update(
                "UPDATE person_activity_schedule SET lease_until = ? WHERE person_id = ?",
                Timestamp.from(afterExpiry.minusSeconds(1)),
                lease.personId().toString()
        );

        assertFalse(repository.renewLease(
                lease,
                afterExpiry.plus(Duration.ofMinutes(10)),
                afterExpiry
        ));
    }

    private static PersonId insertPersonAndSchedule() {
        PersonId personId = PersonId.random();
        jdbcTemplate.update(
                "INSERT INTO digital_person (person_id, version, aggregate_json) VALUES (?, 0, JSON_OBJECT())",
                personId.toString()
        );
        assertEquals(1, repository.initializeMissing(NOW));
        return personId;
    }
}
