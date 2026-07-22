package com.laishengkai.digitalperson.memory;

import java.time.Instant;
import java.util.Objects;

/** One provider-neutral memory item selected for a model request. */
public record MemoryItem(
        String id,
        MemorySection section,
        String content,
        double relevance,
        Instant createdAt,
        Instant updatedAt
) {
    public MemoryItem {
        id = requireText(id, "id");
        section = Objects.requireNonNull(section, "section cannot be null");
        content = requireText(content, "content");
        if (!Double.isFinite(relevance) || relevance < 0.0 || relevance > 1.0) {
            throw new IllegalArgumentException("relevance must be between 0.0 and 1.0");
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
