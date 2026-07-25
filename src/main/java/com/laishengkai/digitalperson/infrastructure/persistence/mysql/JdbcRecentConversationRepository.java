package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.laishengkai.digitalperson.conversation.ConversationSummarySnapshot;
import com.laishengkai.digitalperson.conversation.ConversationSummaryStore;
import com.laishengkai.digitalperson.conversation.ConversationSummaryWorkItem;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;
import com.laishengkai.digitalperson.conversation.RecentConversationStore;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** MySQL adapter for raw dialogue turns and their optimistic rolling summary. */
public final class JdbcRecentConversationRepository
        implements RecentConversationGateway, RecentConversationStore, ConversationSummaryStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            JdbcRecentConversationRepository.class
    );

    private static final String INSERT_SQL = """
            INSERT INTO person_conversation_turn (
                person_id,
                role,
                turn_text,
                occurred_at,
                created_at
            ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))
            """;

    private static final String RETRIEVE_SQL = """
            SELECT role, turn_text, occurred_at
            FROM (
                SELECT
                    conversation_turn_id,
                    role,
                    turn_text,
                    occurred_at
                FROM person_conversation_turn
                WHERE person_id = ?
                  AND conversation_turn_id > COALESCE(
                      (
                          SELECT covered_through_turn_id
                          FROM person_conversation_summary
                          WHERE person_id = ?
                      ),
                      0
                  )
                ORDER BY conversation_turn_id DESC
                LIMIT ?
            ) recent_turns
            ORDER BY conversation_turn_id ASC
            """;

    private static final String SUMMARY_SELECT_SQL = """
            SELECT
                summary_text,
                covered_through_turn_id,
                summarized_turn_count,
                version,
                created_at,
                updated_at
            FROM person_conversation_summary
            WHERE person_id = ?
            """;

    private static final String UNSUMMARIZED_COUNT_SQL = """
            SELECT COUNT(*)
            FROM person_conversation_turn
            WHERE person_id = ?
              AND conversation_turn_id > ?
            """;

    private static final String SUMMARY_BATCH_SQL = """
            SELECT
                conversation_turn_id,
                role,
                turn_text,
                occurred_at
            FROM person_conversation_turn
            WHERE person_id = ?
              AND conversation_turn_id > ?
            ORDER BY conversation_turn_id ASC
            LIMIT ?
            """;

    private static final String SUMMARY_INSERT_SQL = """
            INSERT INTO person_conversation_summary (
                person_id,
                summary_text,
                covered_through_turn_id,
                summarized_turn_count,
                version,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, 0, ?, ?)
            """;

    private static final String SUMMARY_UPDATE_SQL = """
            UPDATE person_conversation_summary
            SET summary_text = ?,
                covered_through_turn_id = ?,
                summarized_turn_count = summarized_turn_count + ?,
                version = version + 1,
                updated_at = ?
            WHERE person_id = ?
              AND version = ?
              AND covered_through_turn_id = ?
            """;

    private static final String RETENTION_CUTOFF_SQL = """
            SELECT conversation_turn_id
            FROM person_conversation_turn
            WHERE person_id = ?
            ORDER BY conversation_turn_id DESC
            LIMIT 1 OFFSET ?
            """;

    private static final String DELETE_OLDER_SQL = """
            DELETE FROM person_conversation_turn
            WHERE person_id = ?
              AND conversation_turn_id <= ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int retentionTurns;

    public JdbcRecentConversationRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            int retentionTurns
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate cannot be null"
        );
        this.transactionTemplate = Objects.requireNonNull(
                transactionTemplate,
                "transactionTemplate cannot be null"
        );
        if (retentionTurns <= 0) {
            throw new IllegalArgumentException("retentionTurns must be positive");
        }
        this.retentionTurns = retentionTurns;
    }

    @Override
    public CompletionStage<List<ConversationTurnSnapshot>> retrieve(
            RecentConversationQuery query
    ) {
        RecentConversationQuery requested = Objects.requireNonNull(
                query,
                "query cannot be null"
        );
        try {
            List<ConversationTurnSnapshot> turns = jdbcTemplate.query(
                    RETRIEVE_SQL,
                    (resultSet, rowNumber) -> new ConversationTurnSnapshot(
                            parseRole(resultSet.getString("role")),
                            resultSet.getString("turn_text"),
                            resultSet.getTimestamp("occurred_at").toInstant()
                    ),
                    requested.personId().toString(),
                    requested.personId().toString(),
                    requested.maxTurns()
            );
            return CompletableFuture.completedFuture(List.copyOf(turns));
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Recent conversation retrieval failed; continuing without raw turns: personId={}",
                    requested.personId(),
                    error
            );
            return CompletableFuture.completedFuture(List.of());
        }
    }

    @Override
    public CompletionStage<Optional<ConversationSummarySnapshot>> retrieve(PersonId personId) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        try {
            return CompletableFuture.completedFuture(findSummary(requestedPersonId));
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Conversation summary retrieval failed; continuing without summary: personId={}",
                    requestedPersonId,
                    error
            );
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override
    public CompletionStage<Optional<ConversationSummaryWorkItem>> findWork(
            PersonId personId,
            int recentTurnsToKeep,
            int batchTurns
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        if (recentTurnsToKeep <= 0 || batchTurns <= 0) {
            throw new IllegalArgumentException("summary turn limits must be positive");
        }

        try {
            Optional<ConversationSummarySnapshot> existing = findSummary(requestedPersonId);
            long coveredThrough = existing
                    .map(ConversationSummarySnapshot::coveredThroughTurnId)
                    .orElse(0L);
            Long unsummarized = jdbcTemplate.queryForObject(
                    UNSUMMARIZED_COUNT_SQL,
                    Long.class,
                    requestedPersonId.toString(),
                    coveredThrough
            );
            long required = Math.addExact((long) recentTurnsToKeep, batchTurns);
            if (Objects.requireNonNullElse(unsummarized, 0L) < required) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            List<StoredTurn> storedTurns = jdbcTemplate.query(
                    SUMMARY_BATCH_SQL,
                    (resultSet, rowNumber) -> new StoredTurn(
                            resultSet.getLong("conversation_turn_id"),
                            new ConversationTurnSnapshot(
                                    parseRole(resultSet.getString("role")),
                                    resultSet.getString("turn_text"),
                                    resultSet.getTimestamp("occurred_at").toInstant()
                            )
                    ),
                    requestedPersonId.toString(),
                    coveredThrough,
                    batchTurns
            );
            if (storedTurns.size() != batchTurns) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            ConversationSummaryWorkItem work = new ConversationSummaryWorkItem(
                    existing,
                    storedTurns.stream().map(StoredTurn::turn).toList(),
                    storedTurns.getLast().id()
            );
            return CompletableFuture.completedFuture(Optional.of(work));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not prepare rolling conversation summary work",
                    error
            ));
        }
    }

    @Override
    public CompletionStage<Boolean> save(
            PersonId personId,
            ConversationSummaryWorkItem workItem,
            String summary,
            Instant summarizedAt
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        ConversationSummaryWorkItem work = Objects.requireNonNull(
                workItem,
                "workItem cannot be null"
        );
        String normalizedSummary = requireText(summary, "summary");
        Instant now = Objects.requireNonNull(summarizedAt, "summarizedAt cannot be null");

        try {
            if (work.existingSummary().isEmpty()) {
                try {
                    int inserted = jdbcTemplate.update(
                            SUMMARY_INSERT_SQL,
                            requestedPersonId.toString(),
                            normalizedSummary,
                            work.coveredThroughTurnId(),
                            work.turns().size(),
                            Timestamp.from(now),
                            Timestamp.from(now)
                    );
                    return CompletableFuture.completedFuture(inserted == 1);
                } catch (DuplicateKeyException conflict) {
                    return CompletableFuture.completedFuture(false);
                }
            }

            int updated = jdbcTemplate.update(
                    SUMMARY_UPDATE_SQL,
                    normalizedSummary,
                    work.coveredThroughTurnId(),
                    work.turns().size(),
                    Timestamp.from(now),
                    requestedPersonId.toString(),
                    work.expectedVersion(),
                    work.expectedCoveredThroughTurnId()
            );
            if (updated > 1) {
                throw new PersonPersistenceException(
                        "conversation summary update modified more than one row"
                );
            }
            return CompletableFuture.completedFuture(updated == 1);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not persist rolling conversation summary",
                    error
            ));
        }
    }

    @Override
    public CompletionStage<Integer> append(
            PersonId personId,
            List<ConversationTurnSnapshot> turns
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        List<ConversationTurnSnapshot> safeTurns = List.copyOf(Objects.requireNonNull(
                turns,
                "turns cannot be null"
        ));
        if (safeTurns.isEmpty()) {
            throw new IllegalArgumentException("turns cannot be empty");
        }
        if (safeTurns.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("turns cannot contain null");
        }

        try {
            Integer stored = transactionTemplate.execute(status -> {
                List<Object[]> arguments = safeTurns.stream()
                        .map(turn -> new Object[]{
                                requestedPersonId.toString(),
                                turn.role().name(),
                                turn.text(),
                                Timestamp.from(turn.occurredAt())
                        })
                        .toList();
                int[] updates = jdbcTemplate.batchUpdate(INSERT_SQL, arguments);
                int inserted = 0;
                for (int updated : updates) {
                    if (updated != 1) {
                        throw new PersonPersistenceException(
                                "conversation turn insertion did not modify exactly one row"
                        );
                    }
                    inserted += updated;
                }
                prune(requestedPersonId);
                return inserted;
            });
            if (stored == null) {
                throw new PersonPersistenceException(
                        "conversation transaction returned no insertion result"
                );
            }
            return CompletableFuture.completedFuture(stored);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not persist recent conversation turns",
                    error
            ));
        }
    }

    private Optional<ConversationSummarySnapshot> findSummary(PersonId personId) {
        return jdbcTemplate.query(
                SUMMARY_SELECT_SQL,
                (resultSet, rowNumber) -> new ConversationSummarySnapshot(
                        resultSet.getString("summary_text"),
                        resultSet.getLong("covered_through_turn_id"),
                        resultSet.getLong("summarized_turn_count"),
                        resultSet.getLong("version"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()
                ),
                personId.toString()
        ).stream().findFirst();
    }

    private void prune(PersonId personId) {
        Optional<Long> cutoff = jdbcTemplate.query(
                RETENTION_CUTOFF_SQL,
                (resultSet, rowNumber) -> resultSet.getLong("conversation_turn_id"),
                personId.toString(),
                retentionTurns
        ).stream().findFirst();
        cutoff.ifPresent(value -> jdbcTemplate.update(
                DELETE_OLDER_SQL,
                personId.toString(),
                value
        ));
    }

    private static ConversationTurnSnapshot.Role parseRole(String value) {
        try {
            return ConversationTurnSnapshot.Role.valueOf(
                    Objects.requireNonNull(value, "stored role cannot be null")
            );
        } catch (IllegalArgumentException error) {
            throw new PersonPersistenceException(
                    "stored conversation role is unsupported",
                    error
            );
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }

    private record StoredTurn(long id, ConversationTurnSnapshot turn) {
        private StoredTurn {
            if (id <= 0) {
                throw new IllegalArgumentException("id must be positive");
            }
            turn = Objects.requireNonNull(turn, "turn cannot be null");
        }
    }
}
