package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.laishengkai.digitalperson.infrastructure.scheduling.PersonActivityScheduleLease;
import com.laishengkai.digitalperson.infrastructure.scheduling.PersonActivityScheduleRepository;
import com.laishengkai.digitalperson.person.PersonId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** MySQL queue adapter with conditional lease acquisition for multi-instance safety. */
public final class JdbcPersonActivityScheduleRepository
        implements PersonActivityScheduleRepository {
    private static final int MAX_ERROR_TYPE_LENGTH = 128;

    private static final String INITIALIZE_MISSING_SQL = """
            INSERT IGNORE INTO person_activity_schedule (
                person_id,
                enabled,
                next_review_at,
                failure_count,
                created_at,
                updated_at
            )
            SELECT person_id, TRUE, ?, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
            FROM digital_person
            """;

    private static final String FIND_DUE_SQL = """
            SELECT person_id, failure_count
            FROM person_activity_schedule
            WHERE enabled = TRUE
              AND next_review_at <= ?
              AND (lease_until IS NULL OR lease_until <= ?)
            ORDER BY next_review_at, person_id
            LIMIT ?
            """;

    private static final String CLAIM_SQL = """
            UPDATE person_activity_schedule
            SET lease_token = ?,
                lease_until = ?,
                last_started_at = ?,
                updated_at = ?
            WHERE person_id = ?
              AND enabled = TRUE
              AND next_review_at <= ?
              AND (lease_until IS NULL OR lease_until <= ?)
            """;

    private static final String COMPLETE_SUCCESS_SQL = """
            UPDATE person_activity_schedule
            SET next_review_at = ?,
                lease_token = NULL,
                lease_until = NULL,
                failure_count = 0,
                last_error_type = NULL,
                last_completed_at = ?,
                updated_at = ?
            WHERE person_id = ?
              AND lease_token = ?
            """;

    private static final String COMPLETE_FAILURE_SQL = """
            UPDATE person_activity_schedule
            SET next_review_at = ?,
                lease_token = NULL,
                lease_until = NULL,
                failure_count = failure_count + 1,
                last_error_type = ?,
                last_completed_at = ?,
                updated_at = ?
            WHERE person_id = ?
              AND lease_token = ?
            """;

    private static final String RESCHEDULE_SQL = """
            UPDATE person_activity_schedule
            SET next_review_at = ?,
                lease_token = NULL,
                lease_until = NULL,
                last_completed_at = ?,
                updated_at = ?
            WHERE person_id = ?
              AND lease_token = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPersonActivityScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate cannot be null"
        );
    }

    @Override
    public int initializeMissing(Instant firstReviewAt) {
        Instant reviewAt = Objects.requireNonNull(
                firstReviewAt,
                "firstReviewAt cannot be null"
        );
        return jdbcTemplate.update(
                INITIALIZE_MISSING_SQL,
                Timestamp.from(reviewAt)
        );
    }

    @Override
    public List<PersonActivityScheduleLease> claimDue(
            Instant now,
            int limit,
            Duration leaseDuration
    ) {
        Instant claimTime = Objects.requireNonNull(now, "now cannot be null");
        Duration duration = Objects.requireNonNull(
                leaseDuration,
                "leaseDuration cannot be null"
        );
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }

        Timestamp nowTimestamp = Timestamp.from(claimTime);
        List<Candidate> candidates = jdbcTemplate.query(
                FIND_DUE_SQL,
                (resultSet, rowNumber) -> new Candidate(
                        PersonId.parse(resultSet.getString("person_id")),
                        resultSet.getInt("failure_count")
                ),
                nowTimestamp,
                nowTimestamp,
                limit
        );

        Instant leaseUntil = claimTime.plus(duration);
        Timestamp leaseUntilTimestamp = Timestamp.from(leaseUntil);
        List<PersonActivityScheduleLease> claimed = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            UUID token = UUID.randomUUID();
            int updated = jdbcTemplate.update(
                    CLAIM_SQL,
                    token.toString(),
                    leaseUntilTimestamp,
                    nowTimestamp,
                    nowTimestamp,
                    candidate.personId().toString(),
                    nowTimestamp,
                    nowTimestamp
            );
            if (updated == 1) {
                claimed.add(new PersonActivityScheduleLease(
                        candidate.personId(),
                        token,
                        candidate.failureCount(),
                        claimTime,
                        leaseUntil
                ));
            } else if (updated > 1) {
                throw new PersonPersistenceException(
                        "activity schedule claim modified more than one row"
                );
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean completeSuccess(
            PersonActivityScheduleLease lease,
            Instant nextReviewAt,
            Instant completedAt
    ) {
        PersonActivityScheduleLease claim = requireLease(lease);
        Instant completion = Objects.requireNonNull(
                completedAt,
                "completedAt cannot be null"
        );
        Instant next = requireAfter(nextReviewAt, completion, "nextReviewAt");
        return exactlyOneOrNone(
                jdbcTemplate.update(
                        COMPLETE_SUCCESS_SQL,
                        Timestamp.from(next),
                        Timestamp.from(completion),
                        Timestamp.from(completion),
                        claim.personId().toString(),
                        claim.leaseToken().toString()
                ),
                "activity schedule success completion"
        );
    }

    @Override
    public boolean completeFailure(
            PersonActivityScheduleLease lease,
            Instant retryAt,
            String errorType,
            Instant completedAt
    ) {
        PersonActivityScheduleLease claim = requireLease(lease);
        Instant completion = Objects.requireNonNull(
                completedAt,
                "completedAt cannot be null"
        );
        Instant retry = requireAfter(retryAt, completion, "retryAt");
        String safeErrorType = normalizeErrorType(errorType);
        return exactlyOneOrNone(
                jdbcTemplate.update(
                        COMPLETE_FAILURE_SQL,
                        Timestamp.from(retry),
                        safeErrorType,
                        Timestamp.from(completion),
                        Timestamp.from(completion),
                        claim.personId().toString(),
                        claim.leaseToken().toString()
                ),
                "activity schedule failure completion"
        );
    }

    @Override
    public boolean rescheduleWithoutFailure(
            PersonActivityScheduleLease lease,
            Instant retryAt,
            Instant completedAt
    ) {
        PersonActivityScheduleLease claim = requireLease(lease);
        Instant completion = Objects.requireNonNull(
                completedAt,
                "completedAt cannot be null"
        );
        Instant retry = requireAfter(retryAt, completion, "retryAt");
        return exactlyOneOrNone(
                jdbcTemplate.update(
                        RESCHEDULE_SQL,
                        Timestamp.from(retry),
                        Timestamp.from(completion),
                        Timestamp.from(completion),
                        claim.personId().toString(),
                        claim.leaseToken().toString()
                ),
                "activity schedule conflict reschedule"
        );
    }

    private static PersonActivityScheduleLease requireLease(
            PersonActivityScheduleLease lease
    ) {
        return Objects.requireNonNull(lease, "lease cannot be null");
    }

    private static Instant requireAfter(Instant value, Instant boundary, String name) {
        Instant instant = Objects.requireNonNull(value, name + " cannot be null");
        if (!instant.isAfter(boundary)) {
            throw new IllegalArgumentException(name + " must be after completedAt");
        }
        return instant;
    }

    private static String normalizeErrorType(String errorType) {
        String value = Objects.requireNonNullElse(errorType, "UnknownFailure").strip();
        if (value.isEmpty()) {
            value = "UnknownFailure";
        }
        return value.length() <= MAX_ERROR_TYPE_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_TYPE_LENGTH);
    }

    private static boolean exactlyOneOrNone(int updatedRows, String operation) {
        if (updatedRows > 1) {
            throw new PersonPersistenceException(operation + " modified more than one row");
        }
        return updatedRows == 1;
    }

    private record Candidate(PersonId personId, int failureCount) {
        private Candidate {
            personId = Objects.requireNonNull(personId, "personId cannot be null");
            if (failureCount < 0) {
                throw new PersonPersistenceException(
                        "activity schedule failure_count cannot be negative"
                );
            }
        }
    }
}