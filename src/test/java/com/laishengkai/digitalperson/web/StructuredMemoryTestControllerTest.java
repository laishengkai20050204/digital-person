package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.memory.MemoryEntity;
import com.laishengkai.digitalperson.memory.MemoryEntityMatch;
import com.laishengkai.digitalperson.memory.MemoryEntityResolutionQuery;
import com.laishengkai.digitalperson.memory.MemoryEntityType;
import com.laishengkai.digitalperson.memory.MemorySection;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredMemoryTestControllerTest {
    private static final String TOKEN = "structured-memory-token";
    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void exposesTypedEntityResolutionAndFactSearchWithoutRawSql() {
        PersonId personId = PersonId.random();
        MemoryEntity entity = new MemoryEntity(
                "entity-1",
                personId,
                MemoryEntityType.PERSON,
                "林晓雨",
                "游戏搭子",
                NOW,
                NOW
        );
        StructuredMemoryFact fact = new StructuredMemoryFact(
                "fact-1",
                personId,
                MemorySection.RELATIONSHIP,
                "SOCIAL",
                entity.entityId(),
                "RELATION_TO_USER",
                "",
                "游戏搭子",
                "林晓雨是用户的游戏搭子",
                0.9,
                0.8,
                2,
                null,
                null,
                NOW,
                NOW,
                NOW
        );
        AtomicReference<StructuredMemoryQuery> receivedSearch = new AtomicReference<>();
        StructuredMemoryRepository repository = new StubRepository() {
            @Override
            public CompletionStage<List<MemoryEntityMatch>> resolve(
                    MemoryEntityResolutionQuery query
            ) {
                return CompletableFuture.completedFuture(List.of(
                        new MemoryEntityMatch(entity, "林晓雨", 0.67)
                ));
            }

            @Override
            public CompletionStage<List<StructuredMemoryMatch>> search(
                    StructuredMemoryQuery query
            ) {
                receivedSearch.set(query);
                return CompletableFuture.completedFuture(List.of(
                        new StructuredMemoryMatch(fact, 0.85)
                ));
            }
        };
        StructuredMemoryTestController controller = new StructuredMemoryTestController(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new MemoryTestApiProperties(true, TOKEN)
        );

        var resolution = controller.resolveEntity(
                personId.toString(),
                TOKEN,
                new StructuredMemoryTestController.EntityResolutionRequest(
                        "林小雨",
                        Set.of("PERSON"),
                        "游戏搭子",
                        0.60,
                        5
                )
        ).toCompletableFuture().join().getBody();
        assertThat(resolution).isNotNull();
        assertThat(resolution.matches()).singleElement().satisfies(match -> {
            assertThat(match.entity().entityId()).isEqualTo("entity-1");
            assertThat(match.similarity()).isEqualTo(0.67);
        });

        var search = controller.search(
                personId.toString(),
                TOKEN,
                new StructuredMemoryTestController.FactSearchRequest(
                        Set.of("RELATIONSHIP"),
                        Set.of("SOCIAL"),
                        Set.of("entity-1"),
                        Set.of("RELATION_TO_USER"),
                        NOW,
                        "那个游戏搭子",
                        5
                )
        ).toCompletableFuture().join().getBody();
        assertThat(search).isNotNull();
        assertThat(search.matches()).singleElement().satisfies(match ->
                assertThat(match.fact().statement())
                        .isEqualTo("林晓雨是用户的游戏搭子")
        );
        assertThat(receivedSearch.get().entityIds()).containsExactly("entity-1");
    }

    @Test
    void rejectsIncorrectTokenBeforeCallingRepository() {
        StructuredMemoryTestController controller = new StructuredMemoryTestController(
                new StubRepository() {
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                new MemoryTestApiProperties(true, TOKEN)
        );

        assertThatThrownBy(() -> controller.search(
                PersonId.random().toString(),
                "wrong",
                new StructuredMemoryTestController.FactSearchRequest(
                        Set.of(), Set.of(), Set.of(), Set.of(), null, "test", 5
                )
        )).isInstanceOf(InvalidInternalTokenException.class);
    }

    private abstract static class StubRepository implements StructuredMemoryRepository {
        @Override
        public CompletionStage<List<StructuredMemoryMatch>> search(
                StructuredMemoryQuery query
        ) {
            throw new UnsupportedOperationException();
        }

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
