package com.laishengkai.digitalperson.conversation;

import java.util.List;
import java.util.Objects;

/** One stable raw-turn batch prepared for automatic structured-memory extraction. */
public record StructuredMemoryExtractionWorkItem(
        List<ConversationTurnSnapshot> turns,
        long sourceStartTurnId,
        long sourceEndTurnId,
        long expectedCoveredThroughTurnId,
        long expectedVersion
) {
    public StructuredMemoryExtractionWorkItem {
        turns = List.copyOf(Objects.requireNonNull(turns, "turns cannot be null"));
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("turns cannot be empty");
        }
        if (turns.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("turns cannot contain null");
        }
        if (sourceStartTurnId <= 0 || sourceEndTurnId < sourceStartTurnId) {
            throw new IllegalArgumentException("invalid structured-memory source range");
        }
        if (expectedCoveredThroughTurnId < 0) {
            throw new IllegalArgumentException(
                    "expectedCoveredThroughTurnId cannot be negative"
            );
        }
        if (sourceStartTurnId <= expectedCoveredThroughTurnId) {
            throw new IllegalArgumentException(
                    "sourceStartTurnId must be after expected coverage"
            );
        }
        if (expectedVersion < -1) {
            throw new IllegalArgumentException("expectedVersion cannot be below -1");
        }
    }
}
