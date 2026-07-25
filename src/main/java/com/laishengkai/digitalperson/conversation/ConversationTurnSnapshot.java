package com.laishengkai.digitalperson.conversation;

import java.time.Instant;
import java.util.Objects;

/** One immutable recent raw conversation turn or synthetic rolling-summary context item. */
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
        SYSTEM,
        /** Synthetic context item loaded from person_conversation_summary, never stored as a raw turn. */
        SUMMARY
    }
}
