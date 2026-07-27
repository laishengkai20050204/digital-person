package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.laishengkai.digitalperson.infrastructure.memory.StructuredMemoryProperties;
import com.laishengkai.digitalperson.memory.MemoryEntity;
import com.laishengkai.digitalperson.memory.MemoryEntityMatch;
import com.laishengkai.digitalperson.memory.MemoryEntityResolutionQuery;
import com.laishengkai.digitalperson.memory.MemoryEntityType;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.MemoryTextNormalizer;
import com.laishengkai.digitalperson.memory.MemoryTextSimilarity;
import com.laishengkai.digitalperson.memory.StructuredMemoryAliasDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryEntityDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryFact;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactConflictMode;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactWriteResult;
import com.laishengkai.digitalperson.memory.StructuredMemoryMatch;
import com.laishengkai.digitalperson.memory.StructuredMemoryQuery;
import com.laishengkai.digitalperson.memory.StructuredMemoryRepository;
import com.laishengkai.digitalperson.person.PersonId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** MySQL implementation of canonical entities, aliases and structured facts. */
public final class JdbcStructuredMemoryRepository implements StructuredMemoryRepository {
    private static final int MIN_FACT_CANDIDATES = 50;
    private static final int FACT_CANDIDATE_MULTIPLIER = 8;
    private static final int MAX_FACT_CANDIDATES = 500;

    private static final String FIND_ENTITY_SQL = """
            SELECT entity_id, person_id, entity_type, canonical_name, description,
                   created_at, updated_at
            FROM memory_entity
            WHERE person_id = ?
              AND entity_type = ?
              AND normalized_name = ?
            """;

    private static final String FIND_ENTITY_BY_ID_SQL = """
            SELECT entity_id, person_id, entity_type, canonical_name, description,
                   created_at, updated_at
            FROM memory_entity
            WHERE entity_id = ?
            """;

    private static final String INSERT_ENTITY_SQL = """
            INSERT INTO memory_entity (
                entity_id,
                person_id,
                entity_type,
                canonical_name,
                normalized_name,
                description,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_ENTITY_SQL = """
            UPDATE memory_entity
            SET canonical_name = ?,
                description = ?,
                updated_at = ?
            WHERE entity_id = ?
            """;

    private static final String UPSERT_ALIAS_SQL = """
            INSERT INTO memory_entity_alias (
                entity_id,
                alias_text,
                normalized_alias,
                source,
                confidence,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                alias_text = VALUES(alias_text),
                source = VALUES(source),
                confidence = GREATEST(confidence, VALUES(confidence)),
                updated_at = VALUES(updated_at)
            """;

    private static final String UPSERT_FACT_SQL = """
            INSERT INTO person_memory_fact (
                fact_id,
                fact_key,
                person_id,
                memory_section,
                domain,
                subject_entity_id,
                predicate,
                object_entity_id,
                text_value,
                statement_text,
                confidence,
                importance,
                evidence_count,
                valid_from,
                valid_until,
                last_confirmed_at,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                statement_text = VALUES(statement_text),
                text_value = VALUES(text_value),
                confidence = GREATEST(confidence, VALUES(confidence)),
                importance = GREATEST(importance, VALUES(importance)),
                evidence_count = evidence_count + 1,
                valid_from = COALESCE(VALUES(valid_from), valid_from),
                valid_until = VALUES(valid_until),
                last_confirmed_at = GREATEST(last_confirmed_at, VALUES(last_confirmed_at)),
                updated_at = VALUES(updated_at)
            """;


    private static final String INSERT_EXTRACTED_FACT_SQL = """
            INSERT INTO person_memory_fact (
                fact_id,
                fact_key,
                person_id,
                memory_section,
                domain,
                subject_entity_id,
                predicate,
                object_entity_id,
                text_value,
                statement_text,
                confidence,
                importance,
                evidence_count,
                valid_from,
                valid_until,
                last_confirmed_at,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_FACT_EVIDENCE_SQL = """
            INSERT INTO person_memory_fact_evidence (
                fact_id,
                source_start_turn_id,
                source_end_turn_id,
                observed_at,
                created_at
            ) VALUES (?, ?, ?, ?, ?)
            """;

    private static final String REFRESH_FACT_FROM_EVIDENCE_SQL = """
            UPDATE person_memory_fact
            SET statement_text = ?,
                text_value = ?,
                confidence = GREATEST(confidence, ?),
                importance = GREATEST(importance, ?),
                evidence_count = evidence_count + 1,
                valid_from = COALESCE(?, valid_from),
                valid_until = ?,
                last_confirmed_at = GREATEST(last_confirmed_at, ?),
                updated_at = ?
            WHERE fact_id = ?
            """;

    private static final String SUPERSEDE_CONFLICTING_FACTS_SQL = """
            UPDATE person_memory_fact
            SET valid_until = ?,
                updated_at = ?
            WHERE person_id = ?
              AND memory_section = ?
              AND domain = ?
              AND subject_entity_id <=> ?
              AND predicate = ?
              AND object_entity_id <=> ?
              AND fact_key <> ?
              AND (valid_until IS NULL OR valid_until > ?)
              AND (valid_from IS NULL OR valid_from < ?)
            """;

    private static final String FIND_FACT_BY_KEY_SQL = """
            SELECT fact_id, person_id, memory_section, domain,
                   subject_entity_id, predicate, object_entity_id,
                   text_value, statement_text, confidence, importance,
                   evidence_count, valid_from, valid_until,
                   last_confirmed_at, created_at, updated_at
            FROM person_memory_fact
            WHERE person_id = ? AND fact_key = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final StructuredMemoryProperties properties;

    public JdbcStructuredMemoryRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            StructuredMemoryProperties properties
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate cannot be null"
        );
        this.transactionTemplate = Objects.requireNonNull(
                transactionTemplate,
                "transactionTemplate cannot be null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties cannot be null"
        );
    }

    @Override
    public CompletionStage<List<StructuredMemoryMatch>> search(
            StructuredMemoryQuery query
    ) {
        StructuredMemoryQuery request = Objects.requireNonNull(
                query,
                "query cannot be null"
        );
        try {
            int candidateLimit = Math.min(
                    MAX_FACT_CANDIDATES,
                    Math.max(
                            MIN_FACT_CANDIDATES,
                            request.maxItems() * FACT_CANDIDATE_MULTIPLIER
                    )
            );
            SqlAndParameters candidateQuery = factSearchSql(request, candidateLimit);
            List<StoredFact> candidates = jdbcTemplate.query(
                    candidateQuery.sql(),
                    (resultSet, rowNumber) -> new StoredFact(
                            mapFact(resultSet),
                            nullableText(resultSet, "subject_name"),
                            nullableText(resultSet, "object_name")
                    ),
                    candidateQuery.parameters().toArray()
            );
            List<StructuredMemoryMatch> ranked = candidates.stream()
                    .map(candidate -> new StructuredMemoryMatch(
                            candidate.fact(),
                            relevance(request.relevanceQuery(), candidate)
                    ))
                    .sorted(Comparator
                            .comparingDouble(StructuredMemoryMatch::relevance)
                            .reversed()
                            .thenComparing(
                                    match -> match.fact().updatedAt(),
                                    Comparator.reverseOrder()
                            )
                            .thenComparing(match -> match.fact().factId()))
                    .limit(request.maxItems())
                    .toList();
            return CompletableFuture.completedFuture(ranked);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not search structured memory",
                    error
            ));
        }
    }

    @Override
    public CompletionStage<List<MemoryEntityMatch>> resolve(
            MemoryEntityResolutionQuery query
    ) {
        MemoryEntityResolutionQuery request = Objects.requireNonNull(
                query,
                "query cannot be null"
        );
        try {
            SqlAndParameters candidateQuery = entityCandidateSql(request);
            List<StoredAlias> candidates = jdbcTemplate.query(
                    candidateQuery.sql(),
                    (resultSet, rowNumber) -> new StoredAlias(
                            mapEntity(resultSet),
                            resultSet.getString("alias_text"),
                            resultSet.getBigDecimal("alias_confidence").doubleValue()
                    ),
                    candidateQuery.parameters().toArray()
            );
            List<MemoryEntityMatch> matches = candidates.stream()
                    .map(candidate -> toMatch(request, candidate))
                    .filter(match -> match.similarity() >= request.minimumSimilarity())
                    .sorted(Comparator
                            .comparingDouble(MemoryEntityMatch::similarity)
                            .reversed()
                            .thenComparing(
                                    match -> match.entity().updatedAt(),
                                    Comparator.reverseOrder()
                            )
                            .thenComparing(match -> match.entity().entityId()))
                    .limit(request.maxCandidates())
                    .toList();
            return CompletableFuture.completedFuture(matches);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not resolve structured-memory entity",
                    error
            ));
        }
    }

    @Override
    public CompletionStage<MemoryEntity> upsertEntity(
            StructuredMemoryEntityDraft draft
    ) {
        StructuredMemoryEntityDraft request = Objects.requireNonNull(
                draft,
                "draft cannot be null"
        );
        try {
            MemoryEntity result = transactionTemplate.execute(status -> {
                String normalizedName = MemoryTextNormalizer.normalize(
                        request.canonicalName()
                );
                MemoryEntity existing = findEntity(
                        request.personId(),
                        request.entityType(),
                        normalizedName
                );
                MemoryEntity entity;
                if (existing == null) {
                    String entityId = UUID.randomUUID().toString();
                    try {
                        jdbcTemplate.update(
                                INSERT_ENTITY_SQL,
                                entityId,
                                request.personId().toString(),
                                request.entityType().name(),
                                request.canonicalName(),
                                normalizedName,
                                request.description(),
                                Timestamp.from(request.observedAt()),
                                Timestamp.from(request.observedAt())
                        );
                        entity = requireEntity(entityId);
                    } catch (DuplicateKeyException duplicate) {
                        entity = requireEntity(
                                Objects.requireNonNull(findEntity(
                                        request.personId(),
                                        request.entityType(),
                                        normalizedName
                                )).entityId()
                        );
                    }
                } else {
                    jdbcTemplate.update(
                            UPDATE_ENTITY_SQL,
                            request.canonicalName(),
                            request.description(),
                            Timestamp.from(request.observedAt()),
                            existing.entityId()
                    );
                    entity = requireEntity(existing.entityId());
                }
                upsertAliasInternal(new StructuredMemoryAliasDraft(
                        entity.entityId(),
                        request.canonicalName(),
                        "CANONICAL",
                        1.0,
                        request.observedAt()
                ));
                return requireEntity(entity.entityId());
            });
            return CompletableFuture.completedFuture(Objects.requireNonNull(result));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not upsert structured-memory entity",
                    error
            ));
        }
    }

    @Override
    public CompletionStage<Void> addAlias(StructuredMemoryAliasDraft draft) {
        StructuredMemoryAliasDraft request = Objects.requireNonNull(
                draft,
                "draft cannot be null"
        );
        try {
            requireEntity(request.entityId());
            upsertAliasInternal(request);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not add structured-memory alias",
                    error
            ));
        }
    }

    @Override
    public CompletionStage<StructuredMemoryFact> upsertFact(
            StructuredMemoryFactDraft draft
    ) {
        StructuredMemoryFactDraft request = Objects.requireNonNull(
                draft,
                "draft cannot be null"
        );
        try {
            StructuredMemoryFact result = transactionTemplate.execute(status -> {
                validateEntityOwnership(request.personId(), request.subjectEntityId());
                validateEntityOwnership(request.personId(), request.objectEntityId());
                String factKey = factKey(request);
                Instant now = request.observedAt();
                jdbcTemplate.update(
                        UPSERT_FACT_SQL,
                        UUID.randomUUID().toString(),
                        factKey,
                        request.personId().toString(),
                        request.section().name(),
                        request.domain(),
                        nullable(request.subjectEntityId()),
                        request.predicate(),
                        nullable(request.objectEntityId()),
                        request.textValue(),
                        request.statement(),
                        BigDecimal.valueOf(request.confidence()),
                        BigDecimal.valueOf(request.importance()),
                        timestamp(request.validFrom()),
                        timestamp(request.validUntil()),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        Timestamp.from(now)
                );
                return jdbcTemplate.query(
                        FIND_FACT_BY_KEY_SQL,
                        resultSet -> resultSet.next() ? mapFact(resultSet) : null,
                        request.personId().toString(),
                        factKey
                );
            });
            return CompletableFuture.completedFuture(Objects.requireNonNull(result));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not upsert structured-memory fact",
                    error
            ));
        }
    }


    @Override
    public CompletionStage<StructuredMemoryFactWriteResult> upsertFactEvidence(
            StructuredMemoryFactDraft draft,
            long sourceStartTurnId,
            long sourceEndTurnId,
            StructuredMemoryFactConflictMode conflictMode
    ) {
        StructuredMemoryFactDraft request = Objects.requireNonNull(
                draft,
                "draft cannot be null"
        );
        StructuredMemoryFactConflictMode requestedConflictMode = Objects.requireNonNull(
                conflictMode,
                "conflictMode cannot be null"
        );
        if (sourceStartTurnId <= 0 || sourceEndTurnId < sourceStartTurnId) {
            throw new IllegalArgumentException("invalid structured-memory evidence range");
        }
        try {
            StructuredMemoryFactWriteResult result = transactionTemplate.execute(status -> {
                validateEntityOwnership(request.personId(), request.subjectEntityId());
                validateEntityOwnership(request.personId(), request.objectEntityId());
                String factKey = factKey(request);
                StructuredMemoryFact fact = findFactByKey(request.personId(), factKey);
                boolean created = false;
                if (fact == null) {
                    try {
                        insertExtractedFact(request, factKey);
                        created = true;
                    } catch (DuplicateKeyException concurrentInsert) {
                        // Another extractor inserted the same semantic fact. Evidence insertion below
                        // decides whether this source batch is new.
                    }
                    fact = Objects.requireNonNull(findFactByKey(request.personId(), factKey));
                }

                boolean evidenceAdded = insertEvidence(
                        fact.factId(),
                        sourceStartTurnId,
                        sourceEndTurnId,
                        request.observedAt()
                );
                if (evidenceAdded && !created) {
                    refreshFactFromEvidence(fact.factId(), request);
                }

                int superseded = 0;
                if (evidenceAdded
                        && requestedConflictMode
                        == StructuredMemoryFactConflictMode.SUPERSEDE_EXISTING) {
                    superseded = supersedeConflictingFacts(request, factKey);
                }
                StructuredMemoryFact stored = Objects.requireNonNull(
                        findFactByKey(request.personId(), factKey)
                );
                return new StructuredMemoryFactWriteResult(
                        stored,
                        evidenceAdded,
                        superseded
                );
            });
            return CompletableFuture.completedFuture(Objects.requireNonNull(result));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new PersonPersistenceException(
                    "could not upsert extracted structured-memory fact evidence",
                    error
            ));
        }
    }

    private void insertExtractedFact(
            StructuredMemoryFactDraft request,
            String factKey
    ) {
        Instant now = request.observedAt();
        jdbcTemplate.update(
                INSERT_EXTRACTED_FACT_SQL,
                UUID.randomUUID().toString(),
                factKey,
                request.personId().toString(),
                request.section().name(),
                request.domain(),
                nullable(request.subjectEntityId()),
                request.predicate(),
                nullable(request.objectEntityId()),
                request.textValue(),
                request.statement(),
                BigDecimal.valueOf(request.confidence()),
                BigDecimal.valueOf(request.importance()),
                timestamp(request.validFrom()),
                timestamp(request.validUntil()),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private boolean insertEvidence(
            String factId,
            long sourceStartTurnId,
            long sourceEndTurnId,
            Instant observedAt
    ) {
        try {
            return jdbcTemplate.update(
                    INSERT_FACT_EVIDENCE_SQL,
                    factId,
                    sourceStartTurnId,
                    sourceEndTurnId,
                    Timestamp.from(observedAt),
                    Timestamp.from(observedAt)
            ) == 1;
        } catch (DuplicateKeyException duplicateEvidence) {
            return false;
        }
    }

    private void refreshFactFromEvidence(
            String factId,
            StructuredMemoryFactDraft request
    ) {
        int updated = jdbcTemplate.update(
                REFRESH_FACT_FROM_EVIDENCE_SQL,
                request.statement(),
                request.textValue(),
                BigDecimal.valueOf(request.confidence()),
                BigDecimal.valueOf(request.importance()),
                timestamp(request.validFrom()),
                timestamp(request.validUntil()),
                Timestamp.from(request.observedAt()),
                Timestamp.from(request.observedAt()),
                factId
        );
        if (updated != 1) {
            throw new PersonPersistenceException(
                    "extracted fact refresh did not modify exactly one row"
            );
        }
    }

    private int supersedeConflictingFacts(
            StructuredMemoryFactDraft request,
            String factKey
    ) {
        Timestamp observedAt = Timestamp.from(request.observedAt());
        return jdbcTemplate.update(
                SUPERSEDE_CONFLICTING_FACTS_SQL,
                observedAt,
                observedAt,
                request.personId().toString(),
                request.section().name(),
                request.domain(),
                nullable(request.subjectEntityId()),
                request.predicate(),
                nullable(request.objectEntityId()),
                factKey,
                observedAt,
                observedAt
        );
    }

    private StructuredMemoryFact findFactByKey(PersonId personId, String factKey) {
        return jdbcTemplate.query(
                FIND_FACT_BY_KEY_SQL,
                resultSet -> resultSet.next() ? mapFact(resultSet) : null,
                personId.toString(),
                factKey
        );
    }

    private SqlAndParameters factSearchSql(
            StructuredMemoryQuery query,
            int candidateLimit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT f.fact_id, f.person_id, f.memory_section, f.domain,
                       f.subject_entity_id, f.predicate, f.object_entity_id,
                       f.text_value, f.statement_text, f.confidence, f.importance,
                       f.evidence_count, f.valid_from, f.valid_until,
                       f.last_confirmed_at, f.created_at, f.updated_at,
                       subject.canonical_name AS subject_name,
                       object_entity.canonical_name AS object_name
                FROM person_memory_fact f
                LEFT JOIN memory_entity subject
                  ON subject.entity_id = f.subject_entity_id
                LEFT JOIN memory_entity object_entity
                  ON object_entity.entity_id = f.object_entity_id
                WHERE f.person_id = ?
                  AND (f.valid_from IS NULL OR f.valid_from <= ?)
                  AND (f.valid_until IS NULL OR f.valid_until > ?)
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(query.personId().toString());
        parameters.add(Timestamp.from(query.validAt()));
        parameters.add(Timestamp.from(query.validAt()));
        appendIn(sql, parameters, "f.memory_section", query.sections().stream()
                .map(Enum::name)
                .toList());
        appendIn(sql, parameters, "f.domain", query.domains());
        appendIn(sql, parameters, "f.predicate", query.predicates());
        if (!query.entityIds().isEmpty()) {
            sql.append(" AND (f.subject_entity_id IN (")
                    .append(placeholders(query.entityIds().size()))
                    .append(") OR f.object_entity_id IN (")
                    .append(placeholders(query.entityIds().size()))
                    .append("))");
            parameters.addAll(query.entityIds());
            parameters.addAll(query.entityIds());
        }
        sql.append(" ORDER BY f.importance DESC, f.confidence DESC, ")
                .append("f.last_confirmed_at DESC, f.fact_id ASC LIMIT ?");
        parameters.add(candidateLimit);
        return new SqlAndParameters(sql.toString(), List.copyOf(parameters));
    }

    private SqlAndParameters entityCandidateSql(MemoryEntityResolutionQuery query) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.entity_id, e.person_id, e.entity_type,
                       e.canonical_name, e.description, e.created_at, e.updated_at,
                       a.alias_text, a.confidence AS alias_confidence
                FROM memory_entity e
                INNER JOIN memory_entity_alias a ON a.entity_id = e.entity_id
                WHERE e.person_id = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(query.personId().toString());
        appendIn(sql, parameters, "e.entity_type", query.entityTypes().stream()
                .map(Enum::name)
                .toList());
        sql.append(" ORDER BY a.confidence DESC, e.updated_at DESC LIMIT ?");
        parameters.add(properties.maximumEntityCandidates());
        return new SqlAndParameters(sql.toString(), List.copyOf(parameters));
    }

    private static void appendIn(
            StringBuilder sql,
            List<Object> parameters,
            String column,
            Iterable<String> values
    ) {
        List<String> safeValues = new ArrayList<>();
        values.forEach(safeValues::add);
        if (safeValues.isEmpty()) {
            return;
        }
        sql.append(" AND ")
                .append(column)
                .append(" IN (")
                .append(placeholders(safeValues.size()))
                .append(')');
        parameters.addAll(safeValues);
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static double relevance(String relevanceQuery, StoredFact candidate) {
        double lexical = MemoryTextSimilarity.similarity(
                relevanceQuery,
                candidate.searchText()
        );
        StructuredMemoryFact fact = candidate.fact();
        double score;
        if (MemoryTextNormalizer.normalize(relevanceQuery).isEmpty()) {
            score = (fact.importance() * 0.60) + (fact.confidence() * 0.40);
        } else {
            score = (lexical * 0.45)
                    + (fact.importance() * 0.35)
                    + (fact.confidence() * 0.20);
        }
        return clamp(score);
    }

    private static MemoryEntityMatch toMatch(
            MemoryEntityResolutionQuery query,
            StoredAlias candidate
    ) {
        double mentionSimilarity = MemoryTextSimilarity.similarity(
                query.mention(),
                candidate.alias()
        );
        double contextSimilarity = MemoryTextSimilarity.similarity(
                query.context(),
                candidate.entity().canonicalName() + ' '
                        + candidate.entity().description()
        );
        double score = mentionSimilarity == 1.0
                ? 1.0
                : clamp(
                        (mentionSimilarity * 0.90)
                                + (contextSimilarity * 0.08)
                                + (candidate.aliasConfidence() * 0.02)
                );
        return new MemoryEntityMatch(
                candidate.entity(),
                candidate.alias(),
                score
        );
    }

    private MemoryEntity findEntity(
            PersonId personId,
            MemoryEntityType type,
            String normalizedName
    ) {
        List<MemoryEntity> matches = jdbcTemplate.query(
                FIND_ENTITY_SQL,
                (resultSet, rowNumber) -> mapEntity(resultSet),
                personId.toString(),
                type.name(),
                normalizedName
        );
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private MemoryEntity requireEntity(String entityId) {
        List<MemoryEntity> matches = jdbcTemplate.query(
                FIND_ENTITY_BY_ID_SQL,
                (resultSet, rowNumber) -> mapEntity(resultSet),
                entityId
        );
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("unknown memory entity: " + entityId);
        }
        return matches.getFirst();
    }

    private void validateEntityOwnership(PersonId personId, String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return;
        }
        MemoryEntity entity = requireEntity(entityId);
        if (!entity.personId().equals(personId)) {
            throw new IllegalArgumentException(
                    "memory entity does not belong to the requested person: " + entityId
            );
        }
    }

    private void upsertAliasInternal(StructuredMemoryAliasDraft draft) {
        String normalizedAlias = MemoryTextNormalizer.normalize(draft.alias());
        if (normalizedAlias.isEmpty()) {
            throw new IllegalArgumentException("alias must contain a letter or digit");
        }
        jdbcTemplate.update(
                UPSERT_ALIAS_SQL,
                draft.entityId(),
                draft.alias(),
                normalizedAlias,
                draft.source(),
                BigDecimal.valueOf(draft.confidence()),
                Timestamp.from(draft.observedAt()),
                Timestamp.from(draft.observedAt())
        );
    }

    private static MemoryEntity mapEntity(ResultSet resultSet) throws SQLException {
        return new MemoryEntity(
                resultSet.getString("entity_id"),
                PersonId.parse(resultSet.getString("person_id")),
                MemoryEntityType.valueOf(resultSet.getString("entity_type")),
                resultSet.getString("canonical_name"),
                nullableText(resultSet, "description"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private static StructuredMemoryFact mapFact(ResultSet resultSet) throws SQLException {
        return new StructuredMemoryFact(
                resultSet.getString("fact_id"),
                PersonId.parse(resultSet.getString("person_id")),
                MemorySection.valueOf(resultSet.getString("memory_section")),
                resultSet.getString("domain"),
                nullableText(resultSet, "subject_entity_id"),
                resultSet.getString("predicate"),
                nullableText(resultSet, "object_entity_id"),
                nullableText(resultSet, "text_value"),
                resultSet.getString("statement_text"),
                resultSet.getBigDecimal("confidence").doubleValue(),
                resultSet.getBigDecimal("importance").doubleValue(),
                resultSet.getInt("evidence_count"),
                instant(resultSet, "valid_from"),
                instant(resultSet, "valid_until"),
                resultSet.getTimestamp("last_confirmed_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private static String factKey(StructuredMemoryFactDraft draft) {
        String canonical = String.join("\u001f",
                draft.personId().toString(),
                draft.section().name(),
                draft.domain(),
                draft.subjectEntityId(),
                draft.predicate(),
                draft.objectEntityId(),
                MemoryTextNormalizer.normalize(draft.textValue())
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static Object nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String nullableText(ResultSet resultSet, String column) throws SQLException {
        return Objects.requireNonNullElse(resultSet.getString(column), "");
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record StoredAlias(
            MemoryEntity entity,
            String alias,
            double aliasConfidence
    ) {
    }

    private record StoredFact(
            StructuredMemoryFact fact,
            String subjectName,
            String objectName
    ) {
        String searchText() {
            return String.join(" ",
                    fact.section().name(),
                    fact.domain(),
                    fact.predicate(),
                    subjectName,
                    objectName,
                    fact.textValue(),
                    fact.statement()
            );
        }
    }

    private record SqlAndParameters(String sql, List<Object> parameters) {
    }
}
