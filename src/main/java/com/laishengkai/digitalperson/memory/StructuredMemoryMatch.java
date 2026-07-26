package com.laishengkai.digitalperson.memory;

import java.util.Objects;

/** Ranked structured fact returned by one restricted query. */
public record StructuredMemoryMatch(
        StructuredMemoryFact fact,
        double relevance
) {
    public StructuredMemoryMatch {
        fact = Objects.requireNonNull(fact, "fact cannot be null");
        if (!Double.isFinite(relevance) || relevance < 0.0 || relevance > 1.0) {
            throw new IllegalArgumentException("relevance must be between 0.0 and 1.0");
        }
    }
}
