package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.memory.MemoryMessageRole;
import com.laishengkai.digitalperson.memory.MemoryMutation;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueMemoryRecorderTest {

    @Test
    void recordsCompletedExchangeForMemoryExtraction() {
        AtomicReference<PersonMemoryWriteRequest> captured = new AtomicReference<>();
        PersonMemoryStore store = new PersonMemoryStore() {
            @Override
            public java.util.concurrent.CompletionStage<List<MemoryMutation>> add(
                    PersonMemoryWriteRequest request
            ) {
                captured.set(request);
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> delete(String memoryId) {
                return CompletableFuture.completedFuture(null);
            }
        };
        DialogueMemoryRecorder recorder = new DialogueMemoryRecorder(store);
        PersonId personId = PersonId.random();
        Instant occurredAt = Instant.parse("2026-07-24T10:00:00Z");

        recorder.record(
                personId,
                "我明天有考试",
                new DialogueResult("安慰并提醒", List.of("我记住了，今晚早点休息。")),
                occurredAt
        ).toCompletableFuture().join();

        PersonMemoryWriteRequest request = captured.get();
        assertEquals(personId, request.personId());
        assertTrue(request.infer());
        assertEquals(2, request.messages().size());
        assertEquals(MemoryMessageRole.USER, request.messages().get(0).role());
        assertEquals(MemoryMessageRole.ASSISTANT, request.messages().get(1).role());
        assertEquals("dialogue", request.metadata().get("source"));
        assertEquals(occurredAt.toString(), request.metadata().get("occurred_at"));
    }

    @Test
    void rejectsAnUnboundedMessageBeforeCallingTheProvider() {
        PersonMemoryStore store = new PersonMemoryStore() {
            @Override
            public java.util.concurrent.CompletionStage<List<MemoryMutation>> add(
                    PersonMemoryWriteRequest request
            ) {
                throw new AssertionError("store must not be called");
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> delete(String memoryId) {
                return CompletableFuture.completedFuture(null);
            }
        };
        DialogueMemoryRecorder recorder = new DialogueMemoryRecorder(store);

        assertThrows(
                IllegalArgumentException.class,
                () -> recorder.record(
                        PersonId.random(),
                        "x".repeat(DialogueMemoryRecorder.MAX_MESSAGE_CHARACTERS + 1),
                        new DialogueResult("", List.of()),
                        Instant.EPOCH
                )
        );
    }
}
