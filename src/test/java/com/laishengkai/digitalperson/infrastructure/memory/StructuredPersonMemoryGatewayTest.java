package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryAvailability;
import com.laishengkai.digitalperson.memory.MemoryEntity;
import com.laishengkai.digitalperson.memory.MemoryEntityMatch;
import com.laishengkai.digitalperson.memory.MemoryEntityResolutionQuery;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.memory.StructuredMemoryAliasDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryEntityDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryFact;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryMatch;
import com.laishengkai.digitalperson.memory.StructuredMemoryQuery;
import com.laishengkai.digitalperson.memory.StructuredMemoryRepository;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredPersonMemoryGatewayTest {
    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void mapsProviderNeutralQueryAndFactResult() {
        PersonId personId = PersonId.random();
        AtomicReference<StructuredMemoryQuery> received = new AtomicReference<>();
        StructuredMemoryFact fact = new StructuredMemoryFact(
                "fact-1",
                personId,
                MemorySection.PREFERENCE,
                "GAME",
                "",
                "LIKES",
                "",
                "马超",
                "用户主要玩马超",
                0.9,
                0.8,
                3,
                null,
                null,
                NOW,
                NOW.minusSeconds(100),
                NOW
        );
        StructuredMemoryRepository repository = new StubRepository() {
            @Override
            public CompletionStage<List<StructuredMemoryMatch>> search(
                    StructuredMemoryQuery query
            ) {
                received.set(query);
                return CompletableFuture.completedFuture(List.of(
                        new StructuredMemoryMatch(fact, 0.88)
                ));
            }
        };

        PersonMemoryContext result = new StructuredPersonMemoryGateway(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        ).retrieve(new PersonMemoryQuery(
                personId,
                "喜欢什么英雄",
                Set.of(MemorySection.PREFERENCE),
                5
        )).toCompletableFuture().join();

        assertThat(received.get().validAt()).isEqualTo(NOW);
        assertThat(received.get().sections())
                .containsExactly(MemorySection.PREFERENCE);
        assertThat(result.availability()).isEqualTo(MemoryAvailability.AVAILABLE);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("structured:fact-1");
            assertThat(item.content()).isEqualTo("用户主要玩马超");
            assertThat(item.relevance()).isEqualTo(0.88);
        });
    }

    @Test
    void failsOpenWhenTheRepositoryFails() {
        StructuredMemoryRepository repository = new StubRepository() {
            @Override
            public CompletionStage<List<StructuredMemoryMatch>> search(
                    StructuredMemoryQuery query
            ) {
                return CompletableFuture.failedFuture(new IllegalStateException("db down"));
            }
        };

        PersonMemoryContext result = new StructuredPersonMemoryGateway(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        ).retrieve(new PersonMemoryQuery(
                PersonId.random(),
                "test",
                Set.of(),
                5
        )).toCompletableFuture().join();

        assertThat(result.availability()).isEqualTo(MemoryAvailability.UNAVAILABLE);
        assertThat(result.items()).isEmpty();
    }

    private abstract static class StubRepository implements StructuredMemoryRepository {
        @Override
        public CompletionStage<List<MemoryEntityMatch>> resolve(
                MemoryEntityResolutionQuery query
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<MemoryEntity> upsertEntity(
                StructuredMemoryEntityDraft draft
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> addAlias(StructuredMemoryAliasDraft draft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<StructuredMemoryFact> upsertFact(
                StructuredMemoryFactDraft draft
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
