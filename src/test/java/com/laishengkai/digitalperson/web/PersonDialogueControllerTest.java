package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.DefaultPersonModelContextAssembler;
import com.laishengkai.digitalperson.application.PersonDialogueService;
import com.laishengkai.digitalperson.dialogue.DialogueResult;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonDialogueControllerTest {
    private static final String TOKEN = "person-api-token";

    @Test
    void exposesGeneratedReplyAndMemoryOutcome() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        PersonDialogueController controller = new PersonDialogueController(
                service(person),
                new PersonApiProperties(true, TOKEN)
        );

        var response = controller.dialogue(
                person.getId().toString(),
                TOKEN,
                new PersonDialogueController.DialogueRequest("你好")
        ).toCompletableFuture().join().getBody();

        assertThat(response).isNotNull();
        assertThat(response.personId()).isEqualTo(person.getId().toString());
        assertThat(response.replies()).containsExactly("你好，我在呢。");
        assertThat(response.occurredAt())
                .isEqualTo(Instant.parse("2026-07-25T01:00:00Z"));
        assertThat(response.memoryStatus()).isEqualTo("DISABLED");
        assertThat(response.memoryMutationCount()).isZero();
    }

    @Test
    void rejectsIncorrectInternalToken() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        PersonDialogueController controller = new PersonDialogueController(
                service(person),
                new PersonApiProperties(true, TOKEN)
        );

        assertThatThrownBy(() -> controller.dialogue(
                person.getId().toString(),
                "wrong-token",
                new PersonDialogueController.DialogueRequest("你好")
        )).isInstanceOf(InvalidInternalTokenException.class);
    }

    private static PersonDialogueService service(Person person) {
        PersonRepository repository = new PersonRepository() {
            @Override
            public Optional<VersionedPerson> findById(PersonId personId) {
                return person.getId().equals(personId)
                        ? Optional.of(new VersionedPerson(person.copy(), 1L))
                        : Optional.empty();
            }

            @Override
            public boolean save(Person updated, long expectedVersion) {
                throw new AssertionError("dialogue must not save the person aggregate");
            }
        };
        return new PersonDialogueService(
                repository,
                DefaultPersonModelContextAssembler.withoutExternalSources(),
                (context, message) -> CompletableFuture.completedFuture(
                        new DialogueResult("", List.of("你好，我在呢。"))
                ),
                null,
                Clock.fixed(
                        Instant.parse("2026-07-25T01:00:00Z"),
                        ZoneOffset.UTC
                ),
                8,
                12
        );
    }
}
