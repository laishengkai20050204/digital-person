package com.laishengkai.digitalperson.memory;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Application-owned structured-memory persistence and query port. */
public interface StructuredMemoryRepository {
    CompletionStage<List<StructuredMemoryMatch>> search(StructuredMemoryQuery query);

    CompletionStage<List<MemoryEntityMatch>> resolve(
            MemoryEntityResolutionQuery query
    );

    CompletionStage<MemoryEntity> upsertEntity(StructuredMemoryEntityDraft draft);

    CompletionStage<Void> addAlias(StructuredMemoryAliasDraft draft);

    CompletionStage<StructuredMemoryFact> upsertFact(StructuredMemoryFactDraft draft);

    default CompletionStage<StructuredMemoryFactWriteResult> upsertFactEvidence(
            StructuredMemoryFactDraft draft,
            long sourceStartTurnId,
            long sourceEndTurnId,
            StructuredMemoryFactConflictMode conflictMode
    ) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "evidence-aware structured-memory writes are unavailable"
                )
        );
    }
}
