package com.laishengkai.digitalperson.conversation;

import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Provides stable dialogue batches and an optimistic extraction checkpoint. */
public interface StructuredMemoryExtractionStore {
    CompletionStage<Optional<StructuredMemoryExtractionWorkItem>> findWork(
            PersonId personId,
            int recentTurnsToKeep,
            int batchTurns
    );

    CompletionStage<Boolean> markCompleted(
            PersonId personId,
            StructuredMemoryExtractionWorkItem workItem,
            int extractedEntityCount,
            int extractedFactCount,
            Instant completedAt
    );
}
