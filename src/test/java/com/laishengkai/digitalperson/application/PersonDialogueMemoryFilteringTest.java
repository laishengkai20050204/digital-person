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

import static org.assertj.core.api.Assertions.assertThat;

class PersonDialogueMemoryFilteringTest {

    @Test
    void reportsProcessedWithZeroMutationsWhenDialogueIsNotMemoryEligible() {
        Person person = Person.create(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        PersonRepository repository = new PersonRepository() {
            @Override
            public Optional<VersionedPerson> findById(PersonId personId) {
                return person.getId().equals(personId)
                        ? Optional.of(new VersionedPerson(person.copy(), 1L))
                        : Optional.empty();
            }

            @Override
            public boolean save(Person updated, long expectedVersion) {
                throw new AssertionError("dialogue must not mutate the person aggregate");
            }
        };
        PersonMemoryStore memoryStore = new PersonMemoryStore() {
            @Override
            public CompletableFuture<List<MemoryMutation>> add(
                    PersonMemoryWriteRequest request
            ) {
                throw new AssertionError("filtered dialogue must not reach Mem0");
            }

            @Override
            public CompletableFuture<Void> delete(String memoryId) {
                return CompletableFuture.completedFuture(null);
            }
        };
        PersonDialogueService service = new PersonDialogueService(
                repository,
                DefaultPersonModelContextAssembler.withoutExternalSources(),
                (context, message) -> CompletableFuture.completedFuture(
                        new DialogueResult("", List.of("测试收到。"))
                ),
                null,
                new DialogueMemoryRecorder(memoryStore),
                Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC),
                8,
                12
        );

        PersonDialogueExchange exchange = service.dialogue(
                person.getId(),
                "这是一条测试消息"
        ).toCompletableFuture().join();

        assertThat(exchange.result().replies()).containsExactly("测试收到。");
        assertThat(exchange.memoryStatus())
                .isEqualTo(PersonDialogueExchange.MemoryStatus.SCHEDULED);
        assertThat(exchange.memoryMutationCount()).isZero();
    }
}
