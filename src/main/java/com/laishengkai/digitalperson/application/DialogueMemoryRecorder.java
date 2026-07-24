package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.memory.MemoryMessage;
import com.laishengkai.digitalperson.memory.MemoryMessageRole;
import com.laishengkai.digitalperson.memory.MemoryMutation;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest;
import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Writes one completed dialogue exchange through the provider-neutral memory port. */
public final class DialogueMemoryRecorder {
    public static final int MAX_MESSAGE_CHARACTERS = 16_000;
    public static final int MAX_EXCHANGE_CHARACTERS = 48_000;

    private final PersonMemoryStore memoryStore;

    public DialogueMemoryRecorder(PersonMemoryStore memoryStore) {
        this.memoryStore = Objects.requireNonNull(
                memoryStore,
                "memoryStore cannot be null"
        );
    }

    public CompletionStage<List<MemoryMutation>> record(
            PersonId personId,
            String userMessage,
            DialogueResult result,
            Instant occurredAt
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        DialogueResult dialogueResult = Objects.requireNonNull(
                result,
                "result cannot be null"
        );
        Instant timestamp = Objects.requireNonNull(
                occurredAt,
                "occurredAt cannot be null"
        );

        List<MemoryMessage> messages = new ArrayList<>();
        messages.add(new MemoryMessage(
                MemoryMessageRole.USER,
                requireBoundedMessage(userMessage, "userMessage")
        ));
        for (String reply : dialogueResult.replies()) {
            String normalized = Objects.requireNonNull(
                    reply,
                    "replies cannot contain null"
            ).strip();
            if (!normalized.isEmpty()) {
                messages.add(new MemoryMessage(
                        MemoryMessageRole.ASSISTANT,
                        requireBoundedMessage(normalized, "reply")
                ));
            }
        }

        int totalCharacters = messages.stream()
                .mapToInt(message -> message.content().length())
                .sum();
        if (totalCharacters > MAX_EXCHANGE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "dialogue exchange cannot exceed "
                            + MAX_EXCHANGE_CHARACTERS
                            + " characters"
            );
        }

        return Objects.requireNonNull(
                memoryStore.add(new PersonMemoryWriteRequest(
                        requestedPersonId,
                        messages,
                        Map.of(
                                "source", "dialogue",
                                "occurred_at", timestamp.toString(),
                                "section", MemorySection.CONVERSATION_SUMMARY.name(),
                                "reply_count", Integer.toString(messages.size() - 1)
                        ),
                        true
                )),
                "memoryStore stage cannot be null"
        );
    }

    private static String requireBoundedMessage(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        if (normalized.length() > MAX_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                    fieldName + " cannot exceed "
                            + MAX_MESSAGE_CHARACTERS
                            + " characters"
            );
        }
        return normalized;
    }
}
