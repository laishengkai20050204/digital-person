package com.laishengkai.digitalperson.memory;

import java.util.Objects;

/** One add, update or delete result returned by a memory provider. */
public record MemoryMutation(
        String memoryId,
        String content,
        String event
) {
    public MemoryMutation {
        memoryId = requireText(memoryId, "memoryId");
        content = Objects.requireNonNullElse(content, "").strip();
        event = requireText(event, "event");
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
