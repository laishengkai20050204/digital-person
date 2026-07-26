package com.laishengkai.digitalperson.infrastructure.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Retrieval limits for the optional structured-memory adapter. */
@ConfigurationProperties(prefix = "digital-person.memory.structured")
public record StructuredMemoryProperties(
        boolean enabled,
        double minimumEntitySimilarity,
        int maximumEntityCandidates
) {
    private static final double DEFAULT_MINIMUM_ENTITY_SIMILARITY = 0.60;
    private static final int DEFAULT_MAXIMUM_ENTITY_CANDIDATES = 300;

    public StructuredMemoryProperties {
        minimumEntitySimilarity = minimumEntitySimilarity == 0.0
                ? DEFAULT_MINIMUM_ENTITY_SIMILARITY
                : minimumEntitySimilarity;
        maximumEntityCandidates = maximumEntityCandidates == 0
                ? DEFAULT_MAXIMUM_ENTITY_CANDIDATES
                : maximumEntityCandidates;
        if (!Double.isFinite(minimumEntitySimilarity)
                || minimumEntitySimilarity < 0.0
                || minimumEntitySimilarity > 1.0) {
            throw new IllegalArgumentException(
                    "minimumEntitySimilarity must be between 0.0 and 1.0"
            );
        }
        if (maximumEntityCandidates < 1 || maximumEntityCandidates > 1_000) {
            throw new IllegalArgumentException(
                    "maximumEntityCandidates must be between 1 and 1000"
            );
        }
    }
}
