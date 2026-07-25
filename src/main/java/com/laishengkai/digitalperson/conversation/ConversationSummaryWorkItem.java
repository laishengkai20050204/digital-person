package com.laishengkai.digitalperson.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One optimistic summary update candidate prepared from older raw turns. */
public record ConversationSummaryWorkItem(
        Optional<ConversationSummarySnapshot> existingSummary,
        List<ConversationTurnSnapshot> turns,
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
        if (coveredThroughTurnId <= 0) {
            throw new IllegalArgumentException("coveredThroughTurnId must be positive");
        }
        existingSummary.ifPresent(summary -> {
            if (coveredThroughTurnId <= summary.coveredThroughTurnId()) {
                throw new IllegalArgumentException(
                        "coveredThroughTurnId must advance the existing summary"
                );
            }
        });
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
