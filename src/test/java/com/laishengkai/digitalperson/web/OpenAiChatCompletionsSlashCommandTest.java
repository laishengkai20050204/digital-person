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
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiChatCompletionsSlashCommandTest {
    private static final String TOKEN = "person-api-token";
    private static final String MODEL = "shen-zhixia";
    private static final Instant NOW = Instant.parse("2026-07-28T03:30:00Z");

    @Test
    void returnsSlashCommandResultWithoutCallingDialogueService() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        AtomicReference<String> dialogueMessage = new AtomicReference<>();
        WechatSlashCommandHandler handler = (personId, message) -> Optional.of(
                new WechatSlashCommandHandler.CommandResult("状态结果", NOW)
        );
        OpenAiChatCompletionsController controller = new OpenAiChatCompletionsController(
                dialogueService(person, dialogueMessage),
                handler,
                new PersonApiProperties(false, TOKEN),
                new OpenAiCompatibilityProperties(true, person.getId().toString(), MODEL),
                JsonMapper.builder().build()
        );

        var httpResponse = controller.complete(
                "Bearer " + TOKEN,
                new OpenAiChatCompletionsController.ChatCompletionRequest(
                        MODEL,
                        List.of(new OpenAiChatCompletionsController.ChatMessage(
                                "user",
                                "/state"
                        )),
                        false
                )
        ).toCompletableFuture().join();

        assertThat(dialogueMessage.get()).isNull();
        assertThat(httpResponse.getBody())
                .isInstanceOf(OpenAiChatCompletionsController.ChatCompletionResponse.class);
        var response = (OpenAiChatCompletionsController.ChatCompletionResponse)
                httpResponse.getBody();
        assertThat(response.choices().getFirst().message().content()).isEqualTo("状态结果");
        assertThat(response.created()).isEqualTo(NOW.getEpochSecond());
    }

    private static PersonDialogueService dialogueService(
            Person person,
            AtomicReference<String> dialogueMessage
    ) {
        PersonRepository repository = new PersonRepository() {
            @Override
            public Optional<VersionedPerson> findById(PersonId personId) {
                return person.getId().equals(personId)
                        ? Optional.of(new VersionedPerson(person.copy(), 1L))
                        : Optional.empty();
            }

            @Override
            public boolean save(Person updated, long expectedVersion) {
                throw new AssertionError("dialogue must not persist in this test");
            }
        };
        return new PersonDialogueService(
                repository,
                DefaultPersonModelContextAssembler.withoutExternalSources(),
                (context, message) -> {
                    dialogueMessage.set(message);
                    return CompletableFuture.completedFuture(new DialogueResult(
                            "",
                            List.of("dialogue reply")
                    ));
                },
                null,
                Clock.fixed(NOW, ZoneOffset.UTC),
                8,
                12
        );
    }
}
