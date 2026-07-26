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
}
