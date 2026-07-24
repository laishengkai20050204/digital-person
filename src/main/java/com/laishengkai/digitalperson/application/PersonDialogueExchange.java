package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Objects;

/** Completed direct dialogue exchange and its non-blocking memory outcome. */
public record PersonDialogueExchange(
        PersonId personId,
        DialogueResult result,
        Instant occurredAt,
        MemoryStatus memoryStatus,
        int memoryMutationCount
) {
    public PersonDialogueExchange {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        result = Objects.requireNonNull(result, "result cannot be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        memoryStatus = Objects.requireNonNull(memoryStatus, "memoryStatus cannot be null");
        if (memoryMutationCount < 0) {
            throw new IllegalArgumentException("memoryMutationCount cannot be negative");
        }
        if (memoryStatus != MemoryStatus.PROCESSED && memoryMutationCount != 0) {
            throw new IllegalArgumentException(
                    "memoryMutationCount must be zero unless memory was processed"
            );
        }
    }

    public enum MemoryStatus {
        PROCESSED,
        DISABLED,
        FAILED
    }
}
