package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.laishengkai.digitalperson.conversation.ConversationEpisodeDraft;
import com.laishengkai.digitalperson.conversation.ConversationEpisodeSnapshot;
import com.laishengkai.digitalperson.conversation.ConversationEpisodeStore;
import com.laishengkai.digitalperson.conversation.ConversationSummaryWorkItem;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** MySQL adapter for complete event memories extracted from stable dialogue batches. */
public final class JdbcConversationEpisodeRepository implements ConversationEpisodeStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            JdbcConversationEpisodeRepository.class
    );
    private static final int MIN_CANDIDATE_LIMIT = 20;
    private static final int CANDIDATE_MULTIPLIER = 8;
    private static final String LABEL_SEPARATOR = "、";

    private static final String SOURCE_START_SQL = """
            SELECT MIN(conversation_turn_id)
            FROM person_conversation_turn
            WHERE person_id = ?
              AND conversation_turn_id > ?
              AND conversation_turn_id <= ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO person_conversation_episode (
                person_id,
                source_start_turn_id,
                source_end_turn_id,
                title,
                summary_text,
                event_type,
                participants_text,
                emotions_text,
                outcome_text,
                importance,
                started_at,
                ended_at,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE episode_id = episode_id
            """;

    private static final String RETRIEVE_SQL = """
            SELECT
                episode_id,
                source_start_turn_id,
                source_end_turn_id,
                title,
                summary_text,
                event_type,
                participants_text,
                emotions_text,
                outcome_text,
                importance,
                started_at,
                ended_at,
                created_at
            FROM person_conversation_episode
            WHERE person_id = ?
            ORDER BY ended_at DESC, importance DESC, episode_id DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcConversationEpisodeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate cannot be null"
        );
    }

    @Override
    public CompletionStage<Integer> saveAll(
            PersonId personId,
            ConversationSummaryWorkItem workItem,
            List<ConversationEpisodeDraft> episodes,
            Instant extractedAt
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        ConversationSummaryWorkItem work = Objects.requireNonNull(
                workItem,
                "workItem cannot be null"
        );
        List<ConversationEpisodeDraft> safeEpisodes = List.copyOf(Objects.requireNonNull(
                episodes,
                "episodes cannot be null"
        ));
        if (safeEpisodes.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("episodes cannot contain null");
        }
        if (safeEpisodes.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        Instant now = Objects.requireNonNull(extractedAt, "extractedAt cannot be null");

        try {
            Long actualStart = jdbcTemplate.queryForObject(
                    SOURCE_START_SQL,
                    Long.class,
                    requestedPersonId.toString(),
                    work.expectedCoveredThroughTurnId(),
                    work.coveredThroughTurnId()
            );
            long sourceStartTurnId = actualStart == null
                    ? work.sourceStartTurnId()
                    : actualStart;
            Instant startedAt = work.turns().getFirst().occurredAt();
            Instant endedAt = work.turns().getLast().occurredAt();
            if (endedAt.isBefore(startedAt)) {
                Instant swap = startedAt;
                startedAt = endedAt;
                endedAt = swap;
            }

            List<Object[]> arguments = new ArrayList<>(safeEpisodes.size());
            for (ConversationEpisodeDraft episode : safeEpisodes) {
                arguments.add(new Object[]{
                        requestedPersonId.toString(),
                        sourceStartTurnId,
                        work.coveredThroughTurnId(),
                        episode.title(),
                        episode.summary(),
                        episode.eventType(),
                        String.join(LABEL_SEPARATOR, episode.participants()),
                        String.join(LABEL_SEPARATOR, episode.emotions()),
                        episode.outcome(),
                        BigDecimal.valueOf(episode.importance()),
                        Timestamp.from(startedAt),
                        Timestamp.from(endedAt),
                        Timestamp.from(now)
                });
            }
            int[] updates = jdbcTemplate.batchUpdate(INSERT_SQL, arguments);
            int inserted = 0;
            for (int update : updates) {
                if (update > 0) {
                    inserted++;
                }
            }
            return CompletableFuture.completedFuture(inserted);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not persist conversation episodes",
                    error
            ));
        }
    }

    @Override
    public CompletionStage<List<ConversationEpisodeSnapshot>> retrieve(
            PersonId personId,
            String relevanceQuery,
            int maxItems
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        String query = Objects.requireNonNull(
                relevanceQuery,
                "relevanceQuery cannot be null"
        ).strip();
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive");
        }
        int candidateLimit = Math.max(
                MIN_CANDIDATE_LIMIT,
                Math.multiplyExact(maxItems, CANDIDATE_MULTIPLIER)
        );

        try {
            List<StoredEpisode> candidates = jdbcTemplate.query(
                    RETRIEVE_SQL,
                    (resultSet, rowNumber) -> new StoredEpisode(
                            resultSet.getLong("episode_id"),
                            new ConversationEpisodeDraft(
                                    resultSet.getString("title"),
                                    resultSet.getString("summary_text"),
                                    resultSet.getString("event_type"),
                                    splitLabels(resultSet.getString("participants_text")),
                                    splitLabels(resultSet.getString("emotions_text")),
                                    resultSet.getString("outcome_text"),
                                    resultSet.getBigDecimal("importance").doubleValue()
                            ),
                            resultSet.getLong("source_start_turn_id"),
                            resultSet.getLong("source_end_turn_id"),
                            resultSet.getTimestamp("started_at").toInstant(),
                            resultSet.getTimestamp("ended_at").toInstant(),
                            resultSet.getTimestamp("created_at").toInstant(),
                            rowNumber
                    ),
                    requestedPersonId.toString(),
                    candidateLimit
            );
            return CompletableFuture.completedFuture(rank(candidates, query, maxItems));
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Conversation episode retrieval failed; continuing without episodes: personId={}",
                    requestedPersonId,
                    error
            );
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private static List<ConversationEpisodeSnapshot> rank(
            List<StoredEpisode> candidates,
            String relevanceQuery,
            int maxItems
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> queryTerms = terms(relevanceQuery);
        int candidateCount = candidates.size();
        return candidates.stream()
                .map(candidate -> {
                    double lexical = lexicalScore(
                            queryTerms,
                            candidate.episode().contextText()
                    );
                    double recency = candidateCount <= 1
                            ? 1.0
                            : 1.0 - ((double) candidate.originalIndex() / (candidateCount - 1));
                    double relevance = queryTerms.isEmpty()
                            ? 0.65 * candidate.episode().importance() + 0.35 * recency
                            : 0.65 * lexical
                                    + 0.25 * candidate.episode().importance()
                                    + 0.10 * recency;
                    return candidate.snapshot(clamp(relevance));
                })
                .sorted(Comparator
                        .comparingDouble(ConversationEpisodeSnapshot::relevance)
                        .reversed()
                        .thenComparing(ConversationEpisodeSnapshot::endedAt, Comparator.reverseOrder())
                        .thenComparingLong(ConversationEpisodeSnapshot::episodeId))
                .limit(maxItems)
                .toList();
    }

    private static double lexicalScore(Set<String> queryTerms, String episodeText) {
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        Set<String> episodeTerms = terms(episodeText);
        long matches = queryTerms.stream().filter(episodeTerms::contains).count();
        return (double) matches / queryTerms.size();
    }

    private static Set<String> terms(String value) {
        String normalized = Normalizer.normalize(
                Objects.requireNonNull(value, "value cannot be null"),
                Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (String token : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2 && token.codePoints().noneMatch(JdbcConversationEpisodeRepository::isHan)) {
                result.add(token);
            }
        }

        String han = normalized.replaceAll("[^\\p{IsHan}]", "");
        int[] codePoints = han.codePoints().toArray();
        for (int index = 0; index + 1 < codePoints.length; index++) {
            result.add(new String(codePoints, index, 2));
        }
        return Set.copyOf(result);
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static List<String> splitLabels(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(normalized.split(LABEL_SEPARATOR))
                .map(String::strip)
                .filter(label -> !label.isEmpty())
                .distinct()
                .toList();
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record StoredEpisode(
            long episodeId,
            ConversationEpisodeDraft episode,
            long sourceStartTurnId,
            long sourceEndTurnId,
            Instant startedAt,
            Instant endedAt,
            Instant createdAt,
            int originalIndex
    ) {
        private StoredEpisode {
            if (episodeId <= 0) {
                throw new IllegalArgumentException("episodeId must be positive");
            }
            episode = Objects.requireNonNull(episode, "episode cannot be null");
            startedAt = Objects.requireNonNull(startedAt, "startedAt cannot be null");
            endedAt = Objects.requireNonNull(endedAt, "endedAt cannot be null");
            createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        }

        private ConversationEpisodeSnapshot snapshot(double relevance) {
            return new ConversationEpisodeSnapshot(
                    episodeId,
                    episode,
                    sourceStartTurnId,
                    sourceEndTurnId,
                    startedAt,
                    endedAt,
                    createdAt,
                    relevance
            );
        }
    }
}
