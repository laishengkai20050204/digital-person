package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.StructuredMemoryExtractionStore;
import com.laishengkai.digitalperson.conversation.StructuredMemoryExtractionWorkItem;
import com.laishengkai.digitalperson.person.PersonId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** MySQL extraction cursor and stable raw-turn batch adapter. */
public final class JdbcStructuredMemoryExtractionStore
        implements StructuredMemoryExtractionStore {
    private static final String CURSOR_SQL = """
            SELECT covered_through_turn_id, version
            FROM person_structured_memory_extraction_cursor
            WHERE person_id = ?
            """;

    private static final String AVAILABLE_COUNT_SQL = """
            SELECT COUNT(*)
            FROM person_conversation_turn
            WHERE person_id = ?
              AND conversation_turn_id > ?
            """;

    private static final String WORK_SQL = """
            SELECT conversation_turn_id, role, turn_text, occurred_at
            FROM person_conversation_turn
            WHERE person_id = ?
              AND conversation_turn_id > ?
            ORDER BY conversation_turn_id ASC
            LIMIT ?
            """;

    private static final String INSERT_CURSOR_SQL = """
            INSERT INTO person_structured_memory_extraction_cursor (
                person_id,
                covered_through_turn_id,
                processed_turn_count,
                version,
                last_entity_count,
                last_fact_count,
                last_completed_at,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_CURSOR_SQL = """
            UPDATE person_structured_memory_extraction_cursor
            SET covered_through_turn_id = ?,
                processed_turn_count = processed_turn_count + ?,
                version = version + 1,
                last_entity_count = ?,
                last_fact_count = ?,
                last_completed_at = ?,
                updated_at = ?
            WHERE person_id = ?
              AND covered_through_turn_id = ?
              AND version = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcStructuredMemoryExtractionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate cannot be null"
        );
    }

    @Override
    public CompletionStage<Optional<StructuredMemoryExtractionWorkItem>> findWork(
            PersonId personId,
            int recentTurnsToKeep,
            int batchTurns
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        if (recentTurnsToKeep < 0 || batchTurns <= 0) {
            throw new IllegalArgumentException("invalid extraction turn limits");
        }
        try {
            Cursor cursor = findCursor(requestedPersonId).orElse(new Cursor(0, -1));
            Long available = jdbcTemplate.queryForObject(
                    AVAILABLE_COUNT_SQL,
                    Long.class,
                    requestedPersonId.toString(),
                    cursor.coveredThroughTurnId()
            );
            long required = Math.addExact((long) recentTurnsToKeep, batchTurns);
            if (Objects.requireNonNullElse(available, 0L) < required) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            List<StoredTurn> turns = jdbcTemplate.query(
                    WORK_SQL,
                    (resultSet, rowNumber) -> new StoredTurn(
                            resultSet.getLong("conversation_turn_id"),
                            new ConversationTurnSnapshot(
                                    parseRole(resultSet.getString("role")),
                                    resultSet.getString("turn_text"),
                                    resultSet.getTimestamp("occurred_at").toInstant()
                            )
                    ),
                    requestedPersonId.toString(),
                    cursor.coveredThroughTurnId(),
                    batchTurns
            );
            if (turns.size() != batchTurns) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return CompletableFuture.completedFuture(Optional.of(
                    new StructuredMemoryExtractionWorkItem(
                            turns.stream().map(StoredTurn::turn).toList(),
                            turns.getFirst().turnId(),
                            turns.getLast().turnId(),
                            cursor.coveredThroughTurnId(),
                            cursor.version()
                    )
            ));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not prepare structured-memory extraction work",
                    error
            ));
        }
    }

    @Override
    public CompletionStage<Boolean> markCompleted(
            PersonId personId,
            StructuredMemoryExtractionWorkItem workItem,
            int extractedEntityCount,
            int extractedFactCount,
            Instant completedAt
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        StructuredMemoryExtractionWorkItem work = Objects.requireNonNull(
                workItem,
                "workItem cannot be null"
        );
        if (extractedEntityCount < 0 || extractedFactCount < 0) {
            throw new IllegalArgumentException("extraction counts cannot be negative");
        }
        Instant now = Objects.requireNonNull(completedAt, "completedAt cannot be null");
        try {
            if (work.expectedVersion() == -1) {
                try {
                    int inserted = jdbcTemplate.update(
                            INSERT_CURSOR_SQL,
                            requestedPersonId.toString(),
                            work.sourceEndTurnId(),
                            work.turns().size(),
                            extractedEntityCount,
                            extractedFactCount,
                            Timestamp.from(now),
                            Timestamp.from(now),
                            Timestamp.from(now)
                    );
                    return CompletableFuture.completedFuture(inserted == 1);
                } catch (DuplicateKeyException conflict) {
                    return CompletableFuture.completedFuture(false);
                }
            }

            int updated = jdbcTemplate.update(
                    UPDATE_CURSOR_SQL,
                    work.sourceEndTurnId(),
                    work.turns().size(),
                    extractedEntityCount,
                    extractedFactCount,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    requestedPersonId.toString(),
                    work.expectedCoveredThroughTurnId(),
                    work.expectedVersion()
            );
            if (updated > 1) {
                throw new PersonPersistenceException(
                        "structured-memory cursor update modified more than one row"
                );
            }
            return CompletableFuture.completedFuture(updated == 1);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not advance structured-memory extraction cursor",
                    error
            ));
        }
    }

    private Optional<Cursor> findCursor(PersonId personId) {
        return jdbcTemplate.query(
                CURSOR_SQL,
                (resultSet, rowNumber) -> new Cursor(
                        resultSet.getLong("covered_through_turn_id"),
                        resultSet.getLong("version")
                ),
                personId.toString()
        ).stream().findFirst();
    }

    private static ConversationTurnSnapshot.Role parseRole(String value) {
        try {
            return ConversationTurnSnapshot.Role.valueOf(
                    Objects.requireNonNull(value, "stored role cannot be null")
            );
        } catch (IllegalArgumentException error) {
            throw new PersonPersistenceException("unknown stored conversation role", error);
        }
    }

    private record Cursor(long coveredThroughTurnId, long version) {
    }

    private record StoredTurn(long turnId, ConversationTurnSnapshot turn) {
    }
}
