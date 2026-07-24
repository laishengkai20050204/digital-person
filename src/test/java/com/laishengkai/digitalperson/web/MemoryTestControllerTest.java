package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.memory.MemoryAvailability;
import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.MemoryMutation;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryTestControllerTest {
    private static final String TOKEN = "memory-test-token";

    @Test
    void writesSearchesAndDeletesThroughProviderNeutralPorts() {
        AtomicReference<String> deletedMemoryId = new AtomicReference<>();
        PersonMemoryStore store = new PersonMemoryStore() {
            @Override
            public CompletableFuture<List<MemoryMutation>> add(
                    com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest request
            ) {
                assertThat(request.messages()).hasSize(2);
                assertThat(request.metadata()).containsEntry("section", "PREFERENCE");
                assertThat(request.infer()).isTrue();
                return CompletableFuture.completedFuture(List.of(new MemoryMutation(
                        "memory-1",
                        "用户喜欢科幻电影",
                        "ADD"
                )));
            }

            @Override
            public CompletableFuture<Void> delete(String memoryId) {
                deletedMemoryId.set(memoryId);
                return CompletableFuture.completedFuture(null);
            }
        };
        PersonMemoryGateway gateway = query -> CompletableFuture.completedFuture(
                new PersonMemoryContext(
                        MemoryAvailability.AVAILABLE,
                        List.of(new MemoryItem(
                                "memory-1",
                                MemorySection.PREFERENCE,
                                "用户喜欢科幻电影",
                                0.91,
                                Instant.parse("2026-07-24T12:00:00Z"),
                                Instant.parse("2026-07-24T12:01:00Z")
                        ))
                )
        );
        MemoryTestController controller = new MemoryTestController(
                store,
                gateway,
                new MemoryTestApiProperties(true, TOKEN)
        );
        PersonId personId = PersonId.random();

        var write = controller.write(
                personId.toString(),
                TOKEN,
                new MemoryTestController.WriteRequest(
                        List.of(
                                new MemoryTestController.MessageRequest("USER", "我喜欢科幻电影"),
                                new MemoryTestController.MessageRequest("ASSISTANT", "我记住了")
                        ),
                        Map.of("section", "PREFERENCE"),
                        true
                )
        ).toCompletableFuture().join().getBody();

        assertThat(write).isNotNull();
        assertThat(write.personId()).isEqualTo(personId.toString());
        assertThat(write.mutations()).containsExactly(new MemoryTestController.MutationResponse(
                "memory-1",
                "用户喜欢科幻电影",
                "ADD"
        ));

        var search = controller.search(
                personId.toString(),
                TOKEN,
                new MemoryTestController.SearchRequest(
                        "喜欢什么电影",
                        Set.of("PREFERENCE"),
                        5
                )
        ).toCompletableFuture().join().getBody();

        assertThat(search).isNotNull();
        assertThat(search.availability()).isEqualTo("AVAILABLE");
        assertThat(search.memories()).hasSize(1);
        assertThat(search.memories().getFirst().memoryId()).isEqualTo("memory-1");
        assertThat(search.memories().getFirst().section()).isEqualTo("PREFERENCE");

        assertThat(controller.delete("memory-1", TOKEN)
                .toCompletableFuture().join().getStatusCode().value()).isEqualTo(204);
        assertThat(deletedMemoryId.get()).isEqualTo("memory-1");
    }

    @Test
    void rejectsMissingOrIncorrectTokens() {
        MemoryTestController controller = new MemoryTestController(
                request -> CompletableFuture.completedFuture(List.of()),
                query -> CompletableFuture.completedFuture(PersonMemoryContext.available(List.of())),
                new MemoryTestApiProperties(true, TOKEN)
        );

        assertThatThrownBy(() -> controller.search(
                PersonId.random().toString(),
                "wrong-token",
                new MemoryTestController.SearchRequest("test", Set.of(), 5)
        )).isInstanceOf(InvalidInternalTokenException.class);
    }

    @Test
    void reportsWhichMem0CapabilityIsNotEnabled() {
        MemoryTestController controller = new MemoryTestController(
                null,
                null,
                new MemoryTestApiProperties(true, TOKEN)
        );
        PersonId personId = PersonId.random();

        assertThatThrownBy(() -> controller.write(
                personId.toString(),
                TOKEN,
                new MemoryTestController.WriteRequest(
                        List.of(new MemoryTestController.MessageRequest("USER", "test")),
                        Map.of(),
                        true
                )
        )).isInstanceOf(MemoryTestUnavailableException.class)
                .hasMessageContaining("writing");

        assertThatThrownBy(() -> controller.search(
                personId.toString(),
                TOKEN,
                new MemoryTestController.SearchRequest("test", Set.of(), 5)
        )).isInstanceOf(MemoryTestUnavailableException.class)
                .hasMessageContaining("retrieval");
    }
}
