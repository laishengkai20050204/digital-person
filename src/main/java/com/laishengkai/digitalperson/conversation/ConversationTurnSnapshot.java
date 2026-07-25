package com.laishengkai.digitalperson.conversation;

import java.time.Instant;
import java.util.Objects;

/** One immutable raw conversation turn or synthetic persisted context item. */
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
        /** Synthetic context item loaded from person_conversation_episode. */
        EPISODE,
        /** Synthetic context item loaded from person_conversation_summary. */
        SUMMARY
    }
}
