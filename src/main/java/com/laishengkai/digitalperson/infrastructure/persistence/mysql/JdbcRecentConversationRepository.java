package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;
import com.laishengkai.digitalperson.conversation.RecentConversationStore;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** MySQL adapter for append-only raw dialogue turns with bounded per-person retention. */
public final class JdbcRecentConversationRepository
        implements RecentConversationGateway, RecentConversationStore {
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
                ORDER BY conversation_turn_id DESC
                LIMIT ?
            ) recent_turns
            ORDER BY conversation_turn_id ASC
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
}
