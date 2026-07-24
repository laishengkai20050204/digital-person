package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.memory.MemoryMutation;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PersonDialogueServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-25T01:00:00Z");

    @Test
    void retrievesContextGeneratesReplyAndRecordsMemory() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        AtomicReference<PersonModelContextAssemblyRequest> contextRequest =
                new AtomicReference<>();
        PersonModelContextAssembler delegate =
                DefaultPersonModelContextAssembler.withoutExternalSources();
        PersonModelContextAssembler assembler = (source, state, evolution, request, time) -> {
            contextRequest.set(request);
            return delegate.assemble(source, state, evolution, request, time);
        };
        AtomicReference<PersonMemoryWriteRequest> memoryRequest = new AtomicReference<>();
        PersonMemoryStore store = new PersonMemoryStore() {
            @Override
            public CompletableFuture<List<MemoryMutation>> add(
                    PersonMemoryWriteRequest request
            ) {
                memoryRequest.set(request);
                return CompletableFuture.completedFuture(List.of(new MemoryMutation(
                        "memory-1",
                        "用户喜欢科幻片",
                        "ADD"
                )));
            }

            @Override
            public CompletableFuture<Void> delete(String memoryId) {
                return CompletableFuture.completedFuture(null);
            }
        };
        PersonDialogueService service = service(
                person,
                assembler,
                new DialogueMemoryRecorder(store)
        );

        PersonDialogueExchange exchange = service.dialogue(
                person.getId(),
                "你还记得我喜欢什么电影吗？"
        ).toCompletableFuture().join();

        assertThat(exchange.result().replies()).containsExactly("当然记得，你喜欢科幻片。");
        assertThat(exchange.occurredAt()).isEqualTo(NOW);
        assertThat(exchange.memoryStatus())
                .isEqualTo(PersonDialogueExchange.MemoryStatus.PROCESSED);
        assertThat(exchange.memoryMutationCount()).isEqualTo(1);
        assertThat(contextRequest.get().relevanceSeed())
                .isEqualTo("你还记得我喜欢什么电影吗？");
        assertThat(contextRequest.get().includeEventContextInRelevanceQuery()).isTrue();
        assertThat(contextRequest.get().maxMemoryItems()).isEqualTo(8);
        assertThat(contextRequest.get().maxConversationTurns()).isEqualTo(12);
        assertThat(memoryRequest.get().personId()).isEqualTo(person.getId());
        assertThat(memoryRequest.get().messages()).hasSize(2);
        assertThat(memoryRequest.get().metadata())
                .containsEntry("section", "CONVERSATION_SUMMARY");
        assertThat(memoryRequest.get().infer()).isTrue();
    }

    @Test
    void returnsReplyWhenMemoryProviderFails() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        PersonMemoryStore failingStore = new PersonMemoryStore() {
            @Override
            public CompletableFuture<List<MemoryMutation>> add(
                    PersonMemoryWriteRequest request
            ) {
                return CompletableFuture.failedFuture(new RuntimeException("mem0 down"));
            }

            @Override
            public CompletableFuture<Void> delete(String memoryId) {
                return CompletableFuture.completedFuture(null);
            }
        };
        PersonDialogueService service = service(
                person,
                DefaultPersonModelContextAssembler.withoutExternalSources(),
                new DialogueMemoryRecorder(failingStore)
        );

        PersonDialogueExchange exchange = service.dialogue(
                person.getId(),
                "你好"
        ).toCompletableFuture().join();

        assertThat(exchange.result().replies()).containsExactly("当然记得，你喜欢科幻片。");
        assertThat(exchange.memoryStatus())
                .isEqualTo(PersonDialogueExchange.MemoryStatus.FAILED);
        assertThat(exchange.memoryMutationCount()).isZero();
    }

    @Test
    void reportsDisabledMemoryWithoutBlockingDialogue() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        PersonDialogueService service = service(
                person,
                DefaultPersonModelContextAssembler.withoutExternalSources(),
                null
        );

        PersonDialogueExchange exchange = service.dialogue(
                person.getId(),
                "你好"
        ).toCompletableFuture().join();

        assertThat(exchange.memoryStatus())
                .isEqualTo(PersonDialogueExchange.MemoryStatus.DISABLED);
        assertThat(exchange.result().replies()).isNotEmpty();
    }

    private static PersonDialogueService service(
            Person person,
            PersonModelContextAssembler assembler,
            DialogueMemoryRecorder recorder
    ) {
        PersonRepository repository = new PersonRepository() {
            @Override
            public Optional<VersionedPerson> findById(PersonId personId) {
                return person.getId().equals(personId)
                        ? Optional.of(new VersionedPerson(person.copy(), 3L))
                        : Optional.empty();
            }

            @Override
            public boolean save(Person updated, long expectedVersion) {
                throw new AssertionError("dialogue must not mutate the person aggregate");
            }
        };
        return new PersonDialogueService(
                repository,
                assembler,
                (context, message) -> CompletableFuture.completedFuture(
                        new DialogueResult("", List.of("当然记得，你喜欢科幻片。"))
                ),
                recorder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                8,
                12
        );
    }
}
