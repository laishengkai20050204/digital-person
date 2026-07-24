package com.laishengkai.digitalperson.memory;

import java.util.Objects;

/** One message supplied to a long-term-memory provider for extraction. */
public record MemoryMessage(
        MemoryMessageRole role,
        String content
) {
    public MemoryMessage {
        role = Objects.requireNonNull(role, "role cannot be null");
        content = Objects.requireNonNull(content, "content cannot be null").strip();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content cannot be blank");
        }
    }
}
