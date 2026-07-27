package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.StructuredMemoryExtractionStore;
import com.laishengkai.digitalperson.conversation.StructuredMemoryExtractionWorkItem;
import com.laishengkai.digitalperson.memory.MemoryEntity;
import com.laishengkai.digitalperson.memory.MemoryEntityMatch;
import com.laishengkai.digitalperson.memory.MemoryEntityResolutionQuery;
import com.laishengkai.digitalperson.memory.MemoryEntityType;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.StructuredMemoryAliasDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryEntityCandidate;
import com.laishengkai.digitalperson.memory.StructuredMemoryEntityDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryExtraction;
import com.laishengkai.digitalperson.memory.StructuredMemoryFact;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactCandidate;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactConflictMode;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactWriteResult;
import com.laishengkai.digitalperson.memory.StructuredMemoryMatch;
import com.laishengkai.digitalperson.memory.StructuredMemoryQuery;
import com.laishengkai.digitalperson.memory.StructuredMemoryRepository;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredMemoryExtractionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-27T00:30:00Z");

    @Test
    void resolvesEntitiesWritesEvidenceAndAdvancesTheCheckpoint() {
        PersonId personId = PersonId.random();
        StructuredMemoryExtractionWorkItem work = work();
        AtomicReference<StructuredMemoryFactDraft> writtenFact = new AtomicReference<>();
        AtomicReference<StructuredMemoryExtractionWorkItem> completed = new AtomicReference<>();
        AtomicReference<StructuredMemoryAliasDraft> alias = new AtomicReference<>();
        MemoryEntity entity = new MemoryEntity(
                "entity-1",
                personId,
                MemoryEntityType.PERSON,
                "林晓雨",
                "用户的游戏搭子",
                NOW,
                NOW
        );

        StructuredMemoryExtractionStore store = new StructuredMemoryExtractionStore() {
            @Override
            public CompletableFuture<Optional<StructuredMemoryExtractionWorkItem>> findWork(
                    PersonId requested,
                    int recentTurnsToKeep,
                    int batchTurns
            ) {
                assertThat(requested).isEqualTo(personId);
                assertThat(recentTurnsToKeep).isEqualTo(2);
                assertThat(batchTurns).isEqualTo(8);
                return CompletableFuture.completedFuture(Optional.of(work));
            }

            @Override
            public CompletableFuture<Boolean> markCompleted(
                    PersonId requested,
                    StructuredMemoryExtractionWorkItem workItem,
                    int extractedEntityCount,
                    int extractedFactCount,
                    Instant completedAt
            ) {
                assertThat(extractedEntityCount).isEqualTo(1);
                assertThat(extractedFactCount).isEqualTo(1);
                completed.set(workItem);
                return CompletableFuture.completedFuture(true);
            }
        };

        StructuredMemoryRepository repository = new StubRepository() {
            @Override
            public CompletableFuture<List<MemoryEntityMatch>> resolve(
                    MemoryEntityResolutionQuery query
            ) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<MemoryEntity> upsertEntity(
                    StructuredMemoryEntityDraft draft
            ) {
                assertThat(draft.canonicalName()).isEqualTo("林晓雨");
                return CompletableFuture.completedFuture(entity);
            }

            @Override
            public CompletableFuture<Void> addAlias(StructuredMemoryAliasDraft draft) {
                alias.set(draft);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<StructuredMemoryFactWriteResult> upsertFactEvidence(
                    StructuredMemoryFactDraft draft,
                    long sourceStartTurnId,
                    long sourceEndTurnId,
                    StructuredMemoryFactConflictMode conflictMode
            ) {
                writtenFact.set(draft);
                assertThat(sourceStartTurnId).isEqualTo(101);
                assertThat(sourceEndTurnId).isEqualTo(108);
                assertThat(conflictMode).isEqualTo(
                        StructuredMemoryFactConflictMode.KEEP_EXISTING
                );
                return CompletableFuture.completedFuture(new StructuredMemoryFactWriteResult(
                        fact(draft),
                        true,
                        0
                ));
            }
        };

        StructuredMemoryExtraction extraction = new StructuredMemoryExtraction(
                List.of(new StructuredMemoryEntityCandidate(
                        "e1",
                        MemoryEntityType.PERSON,
                        "林晓雨",
                        List.of("小林"),
                        "用户的游戏搭子",
                        0.95
                )),
                List.of(new StructuredMemoryFactCandidate(
                        MemorySection.RELATIONSHIP,
                        "SOCIAL",
                        "e1",
                        "RELATION_TO_USER",
                        "",
                        "游戏搭子",
                        "林晓雨是用户的游戏搭子",
                        0.92,
                        0.75,
                        null,
                        null,
                        StructuredMemoryFactConflictMode.KEEP_EXISTING
                ))
        );

        StructuredMemoryExtractionService service = new StructuredMemoryExtractionService(
                store,
                (turns, zone) -> CompletableFuture.completedFuture(extraction),
                repository,
                2,
                8,
                8,
                12,
                0.70,
                0.35
        );

        service.extractIfNeeded(personId, ZoneId.of("Asia/Shanghai"), NOW)
                .toCompletableFuture()
                .join();

        assertThat(alias.get().alias()).isEqualTo("小林");
        assertThat(writtenFact.get().subjectEntityId()).isEqualTo("entity-1");
        assertThat(writtenFact.get().statement()).isEqualTo("林晓雨是用户的游戏搭子");
        assertThat(completed.get()).isEqualTo(work);
    }

    @Test
    void doesNotAdvanceTheCheckpointWhenTheModelFails() {
        PersonId personId = PersonId.random();
        AtomicReference<StructuredMemoryExtractionWorkItem> completed = new AtomicReference<>();
        StructuredMemoryExtractionStore store = new StructuredMemoryExtractionStore() {
            @Override
            public CompletableFuture<Optional<StructuredMemoryExtractionWorkItem>> findWork(
                    PersonId requested,
                    int recentTurnsToKeep,
                    int batchTurns
            ) {
                return CompletableFuture.completedFuture(Optional.of(work()));
            }

            @Override
            public CompletableFuture<Boolean> markCompleted(
                    PersonId requested,
                    StructuredMemoryExtractionWorkItem workItem,
                    int extractedEntityCount,
                    int extractedFactCount,
                    Instant completedAt
            ) {
                completed.set(workItem);
                return CompletableFuture.completedFuture(true);
            }
        };
        StructuredMemoryExtractionService service = new StructuredMemoryExtractionService(
                store,
                (turns, zone) -> CompletableFuture.failedFuture(
                        new RuntimeException("model unavailable")
                ),
                new StubRepository(),
                2,
                8,
                8,
                12,
                0.70,
                0.35
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.extractIfNeeded(
                personId,
                ZoneId.of("UTC"),
                NOW
        ).toCompletableFuture().join()).hasCauseInstanceOf(RuntimeException.class);
        assertThat(completed.get()).isNull();
    }

    private static StructuredMemoryExtractionWorkItem work() {
        return new StructuredMemoryExtractionWorkItem(
                List.of(
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.USER,
                                "我最近认识了林晓雨，经常一起打王者。",
                                NOW.minusSeconds(60)
                        ),
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.PERSON,
                                "听起来她是你的游戏搭子。",
                                NOW
                        )
                ),
                101,
                108,
                100,
                3
        );
    }

    private static StructuredMemoryFact fact(StructuredMemoryFactDraft draft) {
        return new StructuredMemoryFact(
                "fact-1",
                draft.personId(),
                draft.section(),
                draft.domain(),
                draft.subjectEntityId(),
                draft.predicate(),
                draft.objectEntityId(),
                draft.textValue(),
                draft.statement(),
                draft.confidence(),
                draft.importance(),
                1,
                draft.validFrom(),
                draft.validUntil(),
                draft.observedAt(),
                draft.observedAt(),
                draft.observedAt()
        );
    }

    private static class StubRepository implements StructuredMemoryRepository {
        @Override
        public CompletableFuture<List<StructuredMemoryMatch>> search(
                StructuredMemoryQuery query
        ) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<List<MemoryEntityMatch>> resolve(
                MemoryEntityResolutionQuery query
        ) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<MemoryEntity> upsertEntity(
                StructuredMemoryEntityDraft draft
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<Void> addAlias(StructuredMemoryAliasDraft draft) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<StructuredMemoryFact> upsertFact(
                StructuredMemoryFactDraft draft
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
