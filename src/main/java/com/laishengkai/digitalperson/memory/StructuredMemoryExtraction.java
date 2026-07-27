package com.laishengkai.digitalperson.memory;

import java.util.List;
import java.util.Objects;

/** Validated model result for one stable conversation batch. */
public record StructuredMemoryExtraction(
        List<StructuredMemoryEntityCandidate> entities,
        List<StructuredMemoryFactCandidate> facts
) {
    public StructuredMemoryExtraction {
        entities = List.copyOf(Objects.requireNonNullElse(entities, List.of()));
        facts = List.copyOf(Objects.requireNonNullElse(facts, List.of()));
        if (entities.stream().anyMatch(Objects::isNull)
                || facts.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("structured-memory extraction cannot contain null");
        }
    }

    public static StructuredMemoryExtraction empty() {
        return new StructuredMemoryExtraction(List.of(), List.of());
    }
}
