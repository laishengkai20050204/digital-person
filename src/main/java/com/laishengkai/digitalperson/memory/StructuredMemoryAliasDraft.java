package com.laishengkai.digitalperson.memory;

import java.time.Instant;
import java.util.Objects;

/** Validated alias attached to one canonical entity. */
public record StructuredMemoryAliasDraft(
        String entityId,
        String alias,
        String source,
        double confidence,
        Instant observedAt
) {
    public StructuredMemoryAliasDraft {
        entityId = requireText(entityId, "entityId");
        alias = requireText(alias, "alias");
        if (alias.length() > 255) {
            throw new IllegalArgumentException("alias cannot exceed 255 characters");
        }
        source = Objects.requireNonNullElse(source, "MANUAL")
                .strip()
                .toUpperCase(java.util.Locale.ROOT);
        if (source.isEmpty() || source.length() > 32) {
            throw new IllegalArgumentException("source must contain 1 to 32 characters");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt cannot be null");
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
