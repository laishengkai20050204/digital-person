package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationSummarySnapshot;
import com.laishengkai.digitalperson.conversation.ConversationSummaryStore;
import com.laishengkai.digitalperson.conversation.ConversationSummaryWorkItem;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationStore;
import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.experience.EventTimeline;
import com.laishengkai.digitalperson.memory.MemoryMutation;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
                .isEqualTo(PersonDialogueExchange.MemoryStatus.SCHEDULED);
        assertThat(exchange.memoryMutationCount()).isZero();
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
    void projectsStateToTheDialogueTimeBeforeAssemblingContext() {
        Instant previousUpdate = NOW.minusSeconds(6 * 60 * 60);
        Person person = Person.reconstitute(
                PersonId.random(),
                new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9),
                PersonState.baseline(),
                new EventTimeline(),
                new EventTimeline(),
                new StateEvolutionContext(previousUpdate, Map.of(), Set.of())
        );
        AtomicReference<StateEvolutionContext> assembledEvolution = new AtomicReference<>();
        PersonModelContextAssembler delegate =
                DefaultPersonModelContextAssembler.withoutExternalSources();
        PersonModelContextAssembler assembler = (source, state, evolution, request, time) -> {
            assembledEvolution.set(evolution);
            return delegate.assemble(source, state, evolution, request, time);
        };
        PersonDialogueService service = new PersonDialogueService(
                repository(person),
                assembler,
                (context, message) -> CompletableFuture.completedFuture(
                        new DialogueResult("", List.of("当然记得，你喜欢科幻片。"))
                ),
                null,
                null,
                null,
                new PersonCurrentStateProjector(new StateUpdater()),
                Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC),
                8,
                12,
                8
        );

        service.dialogue(person.getId(), "你好").toCompletableFuture().join();

        assertThat(assembledEvolution.get().lastUpdatedAt()).isEqualTo(NOW);
        assertThat(person.getStateEvolutionContext().lastUpdatedAt()).isEqualTo(previousUpdate);
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
                .isEqualTo(PersonDialogueExchange.MemoryStatus.SCHEDULED);
        assertThat(exchange.memoryMutationCount()).isZero();
    }


    @Test
    void returnsWithoutWaitingForMemoryRecordingToFinish() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        CompletableFuture<List<MemoryMutation>> pendingMemory = new CompletableFuture<>();
        PersonMemoryStore store = new PersonMemoryStore() {
            @Override
            public CompletableFuture<List<MemoryMutation>> add(
                    PersonMemoryWriteRequest request
            ) {
                return pendingMemory;
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
                new DialogueMemoryRecorder(store)
        );

        CompletableFuture<PersonDialogueExchange> exchangeFuture = service.dialogue(
                person.getId(),
                "你好"
        ).toCompletableFuture();

        assertThat(exchangeFuture).isCompleted();
        assertThat(exchangeFuture.join().memoryStatus())
                .isEqualTo(PersonDialogueExchange.MemoryStatus.SCHEDULED);
        assertThat(pendingMemory).isNotDone();
        pendingMemory.complete(List.of());
    }


    @Test
    void schedulesRollingSummaryWithoutRunningJdbcWorkOnTheResponseThread() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        AtomicBoolean summaryWorkStarted = new AtomicBoolean();
        ConversationSummaryStore summaryStore = new ConversationSummaryStore() {
            @Override
            public CompletableFuture<Optional<ConversationSummarySnapshot>> retrieve(
                    PersonId personId
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Optional<ConversationSummaryWorkItem>> findWork(
                    PersonId personId,
                    int recentTurnsToKeep,
                    int batchTurns
            ) {
                summaryWorkStarted.set(true);
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Boolean> save(
                    PersonId personId,
                    ConversationSummaryWorkItem workItem,
                    String summary,
                    Instant summarizedAt
            ) {
                throw new AssertionError("no summary work should be available");
            }
        };
        ConversationSummaryService summaryService = new ConversationSummaryService(
                summaryStore,
                (existing, turns, zone) -> CompletableFuture.failedFuture(
                        new AssertionError("summary model must not run")
                ),
                12,
                8
        );
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        RecentConversationStore conversationStore = (personId, turns) ->
                CompletableFuture.completedFuture(turns.size());
        PersonDialogueService service = new PersonDialogueService(
                repository(person),
                DefaultPersonModelContextAssembler.withoutExternalSources(),
                (context, message) -> CompletableFuture.completedFuture(
                        new DialogueResult("", List.of("当然记得，你喜欢科幻片。"))
                ),
                conversationStore,
                summaryService,
                null,
                null,
                queuedTask::set,
                Clock.fixed(NOW, ZoneOffset.UTC),
                8,
                12,
                8
        );

        CompletableFuture<PersonDialogueExchange> exchangeFuture = service.dialogue(
                person.getId(),
                "你好"
        ).toCompletableFuture();

        assertThat(exchangeFuture).isCompleted();
        assertThat(summaryWorkStarted).isFalse();
        assertThat(queuedTask.get()).isNotNull();

        queuedTask.get().run();
        assertThat(summaryWorkStarted).isTrue();
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
        return new PersonDialogueService(
                repository(person),
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

    private static PersonRepository repository(Person person) {
        return new PersonRepository() {
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
    }
}
