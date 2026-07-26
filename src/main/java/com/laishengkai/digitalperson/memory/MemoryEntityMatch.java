package com.laishengkai.digitalperson.memory;

import java.util.Objects;

/** Ranked entity resolution result, including the alias that matched. */
public record MemoryEntityMatch(
        MemoryEntity entity,
        String matchedAlias,
        double similarity
) {
    public MemoryEntityMatch {
        entity = Objects.requireNonNull(entity, "entity cannot be null");
        matchedAlias = Objects.requireNonNull(
                matchedAlias,
                "matchedAlias cannot be null"
        ).strip();
        if (matchedAlias.isEmpty()) {
            throw new IllegalArgumentException("matchedAlias cannot be blank");
        }
        if (!Double.isFinite(similarity) || similarity < 0.0 || similarity > 1.0) {
            throw new IllegalArgumentException("similarity must be between 0.0 and 1.0");
        }
    }
}
