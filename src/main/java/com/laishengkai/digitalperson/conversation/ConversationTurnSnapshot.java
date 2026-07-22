package com.laishengkai.digitalperson.conversation;

import java.time.Instant;
import java.util.Objects;

/** One immutable recent raw conversation turn. */
public record ConversationTurnSnapshot(
        Role role,
        String text,
        Instant occurredAt
) {
    public ConversationTurnSnapshot {
        role = Objects.requireNonNull(role, "role cannot be null");
        text = Objects.requireNonNull(text, "text cannot be null").strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text cannot be blank");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
    }

    public enum Role {
        USER,
        PERSON,
        SYSTEM
    }
}
