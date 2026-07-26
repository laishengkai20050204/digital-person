package com.laishengkai.digitalperson.memory;

import com.laishengkai.digitalperson.person.PersonId;

import java.util.Objects;
import java.util.Set;

/** Restricted entity lookup request used before exact fact queries. */
public record MemoryEntityResolutionQuery(
        PersonId personId,
        String mention,
        Set<MemoryEntityType> entityTypes,
        String context,
        double minimumSimilarity,
        int maxCandidates
) {
    public MemoryEntityResolutionQuery {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        mention = requireText(mention, "mention");
        entityTypes = Set.copyOf(Objects.requireNonNullElse(entityTypes, Set.of()));
        if (entityTypes.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("entityTypes cannot contain null");
        }
        context = Objects.requireNonNullElse(context, "").strip();
        if (!Double.isFinite(minimumSimilarity)
                || minimumSimilarity < 0.0
                || minimumSimilarity > 1.0) {
            throw new IllegalArgumentException(
                    "minimumSimilarity must be between 0.0 and 1.0"
            );
        }
        if (maxCandidates <= 0 || maxCandidates > 100) {
            throw new IllegalArgumentException(
                    "maxCandidates must be between 1 and 100"
            );
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }
}
