package com.laishengkai.digitalperson.conversation;

import java.time.Instant;
import java.util.Objects;

/** One persisted event memory extracted from a stable prefix of raw dialogue. */
public record ConversationEpisodeSnapshot(
        long episodeId,
        ConversationEpisodeDraft episode,
        long sourceStartTurnId,
        long sourceEndTurnId,
        Instant startedAt,
        Instant endedAt,
        Instant createdAt,
        double relevance
) {
    public ConversationEpisodeSnapshot {
        if (episodeId <= 0) {
            throw new IllegalArgumentException("episodeId must be positive");
        }
        episode = Objects.requireNonNull(episode, "episode cannot be null");
        if (sourceStartTurnId <= 0 || sourceEndTurnId < sourceStartTurnId) {
            throw new IllegalArgumentException("source turn range is invalid");
        }
        startedAt = Objects.requireNonNull(startedAt, "startedAt cannot be null");
        endedAt = Objects.requireNonNull(endedAt, "endedAt cannot be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt cannot be before startedAt");
        }
        if (!Double.isFinite(relevance) || relevance < 0.0 || relevance > 1.0) {
            throw new IllegalArgumentException("relevance must be between 0.0 and 1.0");
        }
    }
}
