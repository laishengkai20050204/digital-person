package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Objects;

/** Completed direct dialogue exchange and its non-blocking persistence outcomes. */
public record PersonDialogueExchange(
        PersonId personId,
        DialogueResult result,
        Instant occurredAt,
        ConversationStatus conversationStatus,
        int persistedConversationTurnCount,
        MemoryStatus memoryStatus,
        int memoryMutationCount
) {
    public PersonDialogueExchange {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        result = Objects.requireNonNull(result, "result cannot be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        conversationStatus = Objects.requireNonNull(
                conversationStatus,
                "conversationStatus cannot be null"
        );
        memoryStatus = Objects.requireNonNull(memoryStatus, "memoryStatus cannot be null");
        if (persistedConversationTurnCount < 0) {
            throw new IllegalArgumentException(
                    "persistedConversationTurnCount cannot be negative"
            );
        }
        if (conversationStatus != ConversationStatus.STORED
                && persistedConversationTurnCount != 0) {
            throw new IllegalArgumentException(
                    "persistedConversationTurnCount must be zero unless conversation was stored"
            );
        }
        if (conversationStatus == ConversationStatus.STORED
                && persistedConversationTurnCount == 0) {
            throw new IllegalArgumentException(
                    "persistedConversationTurnCount must be positive when conversation was stored"
            );
        }
        if (memoryMutationCount < 0) {
            throw new IllegalArgumentException("memoryMutationCount cannot be negative");
        }
        if (memoryStatus != MemoryStatus.PROCESSED && memoryMutationCount != 0) {
            throw new IllegalArgumentException(
                    "memoryMutationCount must be zero unless memory was processed"
            );
        }
    }

    public enum ConversationStatus {
        STORED,
        DISABLED,
        FAILED
    }

    public enum MemoryStatus {
        PROCESSED,
        DISABLED,
        FAILED
    }
}
