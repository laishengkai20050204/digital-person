package com.laishengkai.digitalperson.conversation;

import java.time.Instant;
import java.util.Objects;

/** One persisted rolling summary covering an ordered prefix of raw conversation turns. */
public record ConversationSummarySnapshot(
        String content,
        long coveredThroughTurnId,
        long summarizedTurnCount,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public ConversationSummarySnapshot {
        content = Objects.requireNonNull(content, "content cannot be null").strip();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content cannot be blank");
        }
        if (coveredThroughTurnId <= 0) {
            throw new IllegalArgumentException("coveredThroughTurnId must be positive");
        }
        if (summarizedTurnCount <= 0) {
            throw new IllegalArgumentException("summarizedTurnCount must be positive");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
        }
    }
}
