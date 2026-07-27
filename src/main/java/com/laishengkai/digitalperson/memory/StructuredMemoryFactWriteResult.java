package com.laishengkai.digitalperson.memory;

import java.util.Objects;

/** Outcome of an evidence-aware extracted-fact write. */
public record StructuredMemoryFactWriteResult(
        StructuredMemoryFact fact,
        boolean evidenceAdded,
        int supersededFactCount
) {
    public StructuredMemoryFactWriteResult {
        fact = Objects.requireNonNull(fact, "fact cannot be null");
        if (supersededFactCount < 0) {
            throw new IllegalArgumentException("supersededFactCount cannot be negative");
        }
    }
}
