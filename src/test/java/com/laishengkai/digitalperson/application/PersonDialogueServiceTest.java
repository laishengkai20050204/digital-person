package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationStore;
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
    void retrievesContextGeneratesReplyPersistsTurnsAndRecordsMemory() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        AtomicReference<PersonModelContextAssemblyRequest> contextRequest =
                new AtomicReference<>();
        PersonModelContextAssembler delegate =
                DefaultPersonModelContextAssembler.withoutExternalSources();
        PersonModelContextAssembler assembler = (source, state, evolution, request, time) -> {
            contextRequest.set(request);
            return delegate.assemble(source, state, evolution, request, time);
        };
        AtomicReference<List<ConversationTurnSnapshot>> persistedTurns =
                new AtomicReference<>();
        RecentConversationStore conversationStore = (personId, turns) -> {
            persistedTurns.set(List.copyOf(turns));
            return CompletableFuture.completedFuture(turns.size());
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
                conversationStore,
                new DialogueMemoryRecorder(store)
        );

        PersonDialogueExchange exchange = service.dialogue(
                person.getId(),
                "你还记得我喜欢什么电影吗？"
        ).toCompletableFuture().join();

        assertThat(exchange.result().replies()).containsExactly("当然记得，你喜欢科幻片。");
        assertThat(exchange.occurredAt()).isEqualTo(NOW);
        assertThat(exchange.conversationStatus())
                .isEqualTo(PersonDialogueExchange.ConversationStatus.STORED);
        assertThat(exchange.persistedConversationTurnCount()).isEqualTo(2);
        assertThat(exchange.memoryStatus())
                .isEqualTo(PersonDialogueExchange.MemoryStatus.PROCESSED);
        assertThat(exchange.memoryMutationCount()).isEqualTo(1);
        assertThat(contextRequest.get().relevanceSeed())
                .isEqualTo("你还记得我喜欢什么电影吗？");
        assertThat(contextRequest.get().includeEventContextInRelevanceQuery()).isFalse();
        assertThat(contextRequest.get().maxMemoryItems()).isEqualTo(8);
        assertThat(contextRequest.get().maxConversationTurns()).isEqualTo(12);
        assertThat(persistedTurns.get())
                .extracting(ConversationTurnSnapshot::role)
                .containsExactly(
                        ConversationTurnSnapshot.Role.USER,
                        ConversationTurnSnapshot.Role.PERSON
                );
        assertThat(persistedTurns.get())
                .extracting(ConversationTurnSnapshot::text)
                .containsExactly(
                        "你还记得我喜欢什么电影吗？",
                        "当然记得，你喜欢科幻片。"
                );
        assertThat(persistedTurns.get())
                .extracting(ConversationTurnSnapshot::occurredAt)
                .containsOnly(NOW);
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
                null,
                new DialogueMemoryRecorder(failingStore)
        );

        PersonDialogueExchange exchange = service.dialogue(
                person.getId(),
                "你好"
        ).toCompletableFuture().join();

        assertThat(exchange.result().replies()).containsExactly("当然记得，你喜欢科幻片。");
        assertThat(exchange.conversationStatus())
                .isEqualTo(PersonDialogueExchange.ConversationStatus.DISABLED);
        assertThat(exchange.memoryStatus())
                .isEqualTo(PersonDialogueExchange.MemoryStatus.FAILED);
        assertThat(exchange.memoryMutationCount()).isZero();
    }

    @Test
    void returnsReplyWhenConversationPersistenceFails() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        RecentConversationStore failingStore = (personId, turns) ->
                CompletableFuture.failedFuture(new RuntimeException("mysql write failed"));
        PersonDialogueService service = service(
                person,
                DefaultPersonModelContextAssembler.withoutExternalSources(),
                failingStore,
                null
        );

        PersonDialogueExchange exchange = service.dialogue(
                person.getId(),
                "你好"
        ).toCompletableFuture().join();

        assertThat(exchange.result().replies()).containsExactly("当然记得，你喜欢科幻片。");
        assertThat(exchange.conversationStatus())
                .isEqualTo(PersonDialogueExchange.ConversationStatus.FAILED);
        assertThat(exchange.persistedConversationTurnCount()).isZero();
        assertThat(exchange.memoryStatus())
                .isEqualTo(PersonDialogueExchange.MemoryStatus.DISABLED);
    }

    @Test
    void reportsDisabledAuxiliaryPersistenceWithoutBlockingDialogue() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        PersonDialogueService service = service(
                person,
                DefaultPersonModelContextAssembler.withoutExternalSources(),
                null,
                null
        );

        PersonDialogueExchange exchange = service.dialogue(
                person.getId(),
                "你好"
        ).toCompletableFuture().join();

        assertThat(exchange.conversationStatus())
                .isEqualTo(PersonDialogueExchange.ConversationStatus.DISABLED);
        assertThat(exchange.persistedConversationTurnCount()).isZero();
        assertThat(exchange.memoryStatus())
                .isEqualTo(PersonDialogueExchange.MemoryStatus.DISABLED);
        assertThat(exchange.result().replies()).isNotEmpty();
    }

    private static PersonDialogueService service(
            Person person,
            PersonModelContextAssembler assembler,
            RecentConversationStore conversationStore,
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
                conversationStore,
                recorder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                8,
                12
        );
    }
}
