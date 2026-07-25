package com.laishengkai.digitalperson.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One optimistic summary update candidate prepared from older raw turns. */
public record ConversationSummaryWorkItem(
        Optional<ConversationSummarySnapshot> existingSummary,
        List<ConversationTurnSnapshot> turns,
        long sourceStartTurnId,
        long coveredThroughTurnId
) {
    public ConversationSummaryWorkItem {
        existingSummary = Objects.requireNonNull(
                existingSummary,
                "existingSummary cannot be null"
        );
        turns = List.copyOf(Objects.requireNonNull(turns, "turns cannot be null"));
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("turns cannot be empty");
        }
        if (turns.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("turns cannot contain null");
        }
        if (sourceStartTurnId <= 0) {
            throw new IllegalArgumentException("sourceStartTurnId must be positive");
        }
        if (coveredThroughTurnId < sourceStartTurnId) {
            throw new IllegalArgumentException(
                    "coveredThroughTurnId cannot be before sourceStartTurnId"
            );
        }
        long previousCoverage = existingSummary
                .map(ConversationSummarySnapshot::coveredThroughTurnId)
                .orElse(0L);
        if (sourceStartTurnId <= previousCoverage) {
            throw new IllegalArgumentException(
                    "sourceStartTurnId must be after existing summary coverage"
            );
        }
        if (coveredThroughTurnId <= previousCoverage) {
            throw new IllegalArgumentException(
                    "coveredThroughTurnId must advance the existing summary"
            );
        }
    }

    /** Compatibility constructor for callers that do not retain concrete source row ids. */
    public ConversationSummaryWorkItem(
            Optional<ConversationSummarySnapshot> existingSummary,
            List<ConversationTurnSnapshot> turns,
            long coveredThroughTurnId
    ) {
        this(
                existingSummary,
                Objects.requireNonNull(turns, "turns cannot be null"),
                Objects.requireNonNull(existingSummary, "existingSummary cannot be null")
                        .map(ConversationSummarySnapshot::coveredThroughTurnId)
                        .orElse(0L) + 1L,
                coveredThroughTurnId
        );
    }

    public long expectedVersion() {
        return existingSummary.map(ConversationSummarySnapshot::version).orElse(-1L);
    }

    public long expectedCoveredThroughTurnId() {
        return existingSummary
                .map(ConversationSummarySnapshot::coveredThroughTurnId)
                .orElse(0L);
    }
}
