package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.StructuredMemoryExtractionStore;
import com.laishengkai.digitalperson.conversation.StructuredMemoryExtractionWorkItem;
import com.laishengkai.digitalperson.dialogue.StructuredMemoryExtractionModel;
import com.laishengkai.digitalperson.memory.MemoryEntity;
import com.laishengkai.digitalperson.memory.MemoryEntityMatch;
import com.laishengkai.digitalperson.memory.MemoryEntityResolutionQuery;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.StructuredMemoryAliasDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryEntityCandidate;
import com.laishengkai.digitalperson.memory.StructuredMemoryEntityDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryExtraction;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactCandidate;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactConflictMode;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactWriteResult;
import com.laishengkai.digitalperson.memory.StructuredMemoryRepository;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Extracts typed memory from stable raw-turn batches without blocking dialogue replies.
 * Checkpoint advancement is optimistic; evidence writes are source-range idempotent.
 */
public final class StructuredMemoryExtractionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            StructuredMemoryExtractionService.class
    );
    private static final double ENTITY_RESOLUTION_SIMILARITY = 0.88;
    private static final Set<MemorySection> SAFE_SUPERSEDE_SECTIONS = Set.of(
            MemorySection.IDENTITY,
            MemorySection.USER_PROFILE,
            MemorySection.SCHEDULE,
            MemorySection.WORKING_MEMORY
    );

    private final StructuredMemoryExtractionStore extractionStore;
    private final StructuredMemoryExtractionModel extractionModel;
    private final StructuredMemoryRepository memoryRepository;
    private final DialogueMemoryRetentionPolicy retentionPolicy;
    private final int recentTurnsToKeep;
    private final int batchTurns;
    private final int maximumEntities;
    private final int maximumFacts;
    private final double minimumConfidence;
    private final double minimumImportance;

    public StructuredMemoryExtractionService(
            StructuredMemoryExtractionStore extractionStore,
            StructuredMemoryExtractionModel extractionModel,
            StructuredMemoryRepository memoryRepository,
            int recentTurnsToKeep,
            int batchTurns,
            int maximumEntities,
            int maximumFacts,
            double minimumConfidence,
            double minimumImportance
    ) {
        this.extractionStore = Objects.requireNonNull(
                extractionStore,
                "extractionStore cannot be null"
        );
        this.extractionModel = Objects.requireNonNull(
                extractionModel,
                "extractionModel cannot be null"
        );
        this.memoryRepository = Objects.requireNonNull(
                memoryRepository,
                "memoryRepository cannot be null"
        );
        this.retentionPolicy = new DialogueMemoryRetentionPolicy();
        this.recentTurnsToKeep = positive(recentTurnsToKeep, "recentTurnsToKeep");
        this.batchTurns = positive(batchTurns, "batchTurns");
        this.maximumEntities = positive(maximumEntities, "maximumEntities");
        this.maximumFacts = positive(maximumFacts, "maximumFacts");
        this.minimumConfidence = unitInterval(minimumConfidence, "minimumConfidence");
        this.minimumImportance = unitInterval(minimumImportance, "minimumImportance");
    }

    public CompletionStage<Void> extractIfNeeded(
            PersonId personId,
            ZoneId localTimeZone,
            Instant completedAt
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        ZoneId zone = Objects.requireNonNull(
                localTimeZone,
                "localTimeZone cannot be null"
        );
        Instant now = Objects.requireNonNull(completedAt, "completedAt cannot be null");

        final CompletionStage<Optional<StructuredMemoryExtractionWorkItem>> workStage;
        try {
            workStage = Objects.requireNonNull(
                    extractionStore.findWork(
                            requestedPersonId,
                            recentTurnsToKeep,
                            batchTurns
                    ),
                    "extractionStore work stage cannot be null"
            );
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }

        return workStage.thenCompose(work -> {
            Optional<StructuredMemoryExtractionWorkItem> candidate = Objects.requireNonNull(
                    work,
                    "structured-memory work result cannot be null"
            );
            if (candidate.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            StructuredMemoryExtractionWorkItem item = candidate.orElseThrow();
            return invokeModel(item, zone)
                    .thenCompose(extraction -> apply(
                            requestedPersonId,
                            item,
                            extraction
                    ))
                    .thenCompose(outcome -> markCompleted(
                            requestedPersonId,
                            item,
                            outcome,
                            now
                    ));
        });
    }

    private CompletionStage<StructuredMemoryExtraction> invokeModel(
            StructuredMemoryExtractionWorkItem item,
            ZoneId zone
    ) {
        try {
            return Objects.requireNonNull(
                    extractionModel.extract(item.turns(), zone),
                    "structured-memory model stage cannot be null"
            ).thenApply(result -> Objects.requireNonNull(
                    result,
                    "structured-memory model result cannot be null"
            ));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletionStage<ApplyOutcome> apply(
            PersonId personId,
            StructuredMemoryExtractionWorkItem item,
            StructuredMemoryExtraction extraction
    ) {
        List<StructuredMemoryEntityCandidate> entityCandidates = extraction.entities()
                .stream()
                .filter(entity -> entity.confidence() >= minimumConfidence)
                .limit(maximumEntities)
                .toList();
        return resolveEntities(personId, entityCandidates, item)
                .thenCompose(resolved -> persistFacts(
                        personId,
                        item,
                        extraction.facts(),
                        resolved
                ).thenApply(factOutcome -> new ApplyOutcome(
                        resolved.size(),
                        factOutcome.persistedFactCount(),
                        factOutcome.addedEvidenceCount(),
                        factOutcome.supersededFactCount()
                )));
    }

    private CompletionStage<Map<String, MemoryEntity>> resolveEntities(
            PersonId personId,
            List<StructuredMemoryEntityCandidate> candidates,
            StructuredMemoryExtractionWorkItem item
    ) {
        CompletionStage<Map<String, MemoryEntity>> stage = CompletableFuture.completedFuture(
                new LinkedHashMap<>()
        );
        for (StructuredMemoryEntityCandidate candidate : candidates) {
            stage = stage.thenCompose(resolved -> resolveEntity(
                    personId,
                    candidate,
                    item
            ).thenApply(entity -> {
                resolved.put(candidate.reference(), entity);
                return resolved;
            }));
        }
        return stage.thenApply(Map::copyOf);
    }

    private CompletionStage<MemoryEntity> resolveEntity(
            PersonId personId,
            StructuredMemoryEntityCandidate candidate,
            StructuredMemoryExtractionWorkItem item
    ) {
        MemoryEntityResolutionQuery query = new MemoryEntityResolutionQuery(
                personId,
                candidate.canonicalName(),
                Set.of(candidate.entityType()),
                candidate.description(),
                ENTITY_RESOLUTION_SIMILARITY,
                3
        );
        return memoryRepository.resolve(query).thenCompose(matches -> {
            List<MemoryEntityMatch> safeMatches = List.copyOf(Objects.requireNonNull(
                    matches,
                    "entity resolution result cannot be null"
            ));
            CompletionStage<MemoryEntity> entityStage;
            if (!safeMatches.isEmpty()
                    && safeMatches.getFirst().similarity() >= ENTITY_RESOLUTION_SIMILARITY) {
                entityStage = CompletableFuture.completedFuture(
                        safeMatches.getFirst().entity()
                );
            } else {
                entityStage = memoryRepository.upsertEntity(
                        new StructuredMemoryEntityDraft(
                                personId,
                                candidate.entityType(),
                                candidate.canonicalName(),
                                candidate.description(),
                                item.turns().getLast().occurredAt()
                        )
                );
            }
            return entityStage.thenCompose(entity -> persistAliases(
                    entity,
                    candidate.aliases(),
                    item.turns().getLast().occurredAt()
            ));
        });
    }

    private CompletionStage<MemoryEntity> persistAliases(
            MemoryEntity entity,
            List<String> aliases,
            Instant observedAt
    ) {
        CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
        for (String alias : aliases) {
            stage = stage.thenCompose(ignored -> memoryRepository.addAlias(
                    new StructuredMemoryAliasDraft(
                            entity.entityId(),
                            alias,
                            "DIALOGUE_EXTRACTION",
                            0.90,
                            observedAt
                    )
            ));
        }
        return stage.thenApply(ignored -> entity);
    }

    private CompletionStage<FactOutcome> persistFacts(
            PersonId personId,
            StructuredMemoryExtractionWorkItem item,
            List<StructuredMemoryFactCandidate> candidates,
            Map<String, MemoryEntity> resolvedEntities
    ) {
        List<StructuredMemoryFactCandidate> eligible = candidates.stream()
                .filter(this::eligibleFact)
                .limit(maximumFacts)
                .toList();
        CompletionStage<FactOutcome> stage = CompletableFuture.completedFuture(
                new FactOutcome(0, 0, 0)
        );
        for (StructuredMemoryFactCandidate candidate : eligible) {
            Optional<StructuredMemoryFactDraft> draft = toDraft(
                    personId,
                    item,
                    candidate,
                    resolvedEntities
            );
            if (draft.isEmpty()) {
                continue;
            }
            StructuredMemoryFactConflictMode conflictMode = safeConflictMode(candidate);
            stage = stage.thenCompose(outcome -> memoryRepository.upsertFactEvidence(
                    draft.orElseThrow(),
                    item.sourceStartTurnId(),
                    item.sourceEndTurnId(),
                    conflictMode
            ).thenApply(write -> outcome.add(write)));
        }
        return stage;
    }

    private boolean eligibleFact(StructuredMemoryFactCandidate candidate) {
        return candidate.confidence() >= minimumConfidence
                && candidate.importance() >= minimumImportance
                && retentionPolicy.shouldRecord(candidate.statement());
    }

    private Optional<StructuredMemoryFactDraft> toDraft(
            PersonId personId,
            StructuredMemoryExtractionWorkItem item,
            StructuredMemoryFactCandidate candidate,
            Map<String, MemoryEntity> resolvedEntities
    ) {
        String subjectId = resolveReference(candidate.subjectReference(), resolvedEntities);
        String objectId = resolveReference(candidate.objectReference(), resolvedEntities);
        if (!candidate.subjectReference().isBlank() && subjectId.isBlank()) {
            return Optional.empty();
        }
        if (!candidate.objectReference().isBlank() && objectId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StructuredMemoryFactDraft(
                    personId,
                    candidate.section(),
                    candidate.domain(),
                    subjectId,
                    candidate.predicate(),
                    objectId,
                    candidate.textValue(),
                    candidate.statement(),
                    candidate.confidence(),
                    candidate.importance(),
                    candidate.validFrom(),
                    candidate.validUntil(),
                    item.turns().getLast().occurredAt()
            ));
        } catch (IllegalArgumentException error) {
            LOGGER.debug(
                    "Dropped invalid extracted structured-memory fact: personId={}, predicate={}",
                    personId,
                    candidate.predicate(),
                    error
            );
            return Optional.empty();
        }
    }

    private static String resolveReference(
            String reference,
            Map<String, MemoryEntity> resolvedEntities
    ) {
        if (reference == null || reference.isBlank()) {
            return "";
        }
        MemoryEntity entity = resolvedEntities.get(reference.strip());
        return entity == null ? "" : entity.entityId();
    }

    private static StructuredMemoryFactConflictMode safeConflictMode(
            StructuredMemoryFactCandidate candidate
    ) {
        if (candidate.conflictMode() == StructuredMemoryFactConflictMode.SUPERSEDE_EXISTING
                && candidate.confidence() >= 0.85
                && SAFE_SUPERSEDE_SECTIONS.contains(candidate.section())) {
            return StructuredMemoryFactConflictMode.SUPERSEDE_EXISTING;
        }
        return StructuredMemoryFactConflictMode.KEEP_EXISTING;
    }

    private CompletionStage<Void> markCompleted(
            PersonId personId,
            StructuredMemoryExtractionWorkItem item,
            ApplyOutcome outcome,
            Instant completedAt
    ) {
        return extractionStore.markCompleted(
                personId,
                item,
                outcome.entityCount(),
                outcome.factCount(),
                completedAt
        ).thenApply(saved -> {
            if (!Boolean.TRUE.equals(saved)) {
                LOGGER.info(
                        "Structured-memory extraction checkpoint lost optimistic race: personId={}, sourceEndTurnId={}",
                        personId,
                        item.sourceEndTurnId()
                );
                return null;
            }
            LOGGER.info(
                    "Structured-memory extraction completed: personId={}, sourceStartTurnId={}, sourceEndTurnId={}, entityCount={}, factCount={}, evidenceAdded={}, supersededCount={}",
                    personId,
                    item.sourceStartTurnId(),
                    item.sourceEndTurnId(),
                    outcome.entityCount(),
                    outcome.factCount(),
                    outcome.addedEvidenceCount(),
                    outcome.supersededFactCount()
            );
            return null;
        });
    }

    private static int positive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static double unitInterval(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    fieldName + " must be between 0.0 and 1.0"
            );
        }
        return value;
    }

    private record FactOutcome(
            int persistedFactCount,
            int addedEvidenceCount,
            int supersededFactCount
    ) {
        FactOutcome add(StructuredMemoryFactWriteResult write) {
            Objects.requireNonNull(write, "write cannot be null");
            return new FactOutcome(
                    persistedFactCount + 1,
                    addedEvidenceCount + (write.evidenceAdded() ? 1 : 0),
                    supersededFactCount + write.supersededFactCount()
            );
        }
    }

    private record ApplyOutcome(
            int entityCount,
            int factCount,
            int addedEvidenceCount,
            int supersededFactCount
    ) {
    }
}
