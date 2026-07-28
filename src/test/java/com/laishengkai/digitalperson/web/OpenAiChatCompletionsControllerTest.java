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
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiChatCompletionsControllerTest {
    private static final String TOKEN = "person-api-token";
    private static final String MODEL = "shen-zhixia";

    @Test
    void adaptsLastUserMessageAndReturnsOpenAiShape() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        OpenAiChatCompletionsController controller = controller(
                person,
                receivedMessage
        );

        var httpResponse = controller.complete(
                "Bearer " + TOKEN,
                new OpenAiChatCompletionsController.ChatCompletionRequest(
                        MODEL,
                        List.of(
                                new OpenAiChatCompletionsController.ChatMessage(
                                        "system",
                                        "OpenClaw transport prompt"
                                ),
                                new OpenAiChatCompletionsController.ChatMessage(
                                        "user",
                                        "第一条"
                                ),
                                new OpenAiChatCompletionsController.ChatMessage(
                                        "assistant",
                                        "旧回复"
                                ),
                                new OpenAiChatCompletionsController.ChatMessage(
                                        "user",
                                        "微信里的最新消息"
                                )
                        ),
                        false
                )
        ).toCompletableFuture().join();

        assertThat(receivedMessage.get()).isEqualTo("微信里的最新消息");
        assertThat(httpResponse.getBody())
                .isInstanceOf(OpenAiChatCompletionsController.ChatCompletionResponse.class);
        var response = (OpenAiChatCompletionsController.ChatCompletionResponse)
                httpResponse.getBody();
        assertThat(response.id()).startsWith("chatcmpl-");
        assertThat(response.object()).isEqualTo("chat.completion");
        assertThat(response.created()).isEqualTo(1784941200L);
        assertThat(response.model()).isEqualTo(MODEL);
        assertThat(response.choices()).hasSize(1);
        assertThat(response.choices().getFirst().index()).isZero();
        assertThat(response.choices().getFirst().message().role()).isEqualTo("assistant");
        assertThat(response.choices().getFirst().message().content())
                .isEqualTo("第一段回复\n\n第二段回复");
        assertThat(response.choices().getFirst().finish_reason()).isEqualTo("stop");
        assertThat(response.usage().total_tokens()).isZero();
    }

    @Test
    void acceptsStructuredTextContentFromOpenClaw() throws Exception {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        OpenAiChatCompletionsController controller = controller(
                person,
                receivedMessage
        );
        JsonMapper mapper = JsonMapper.builder().build();

        var request = mapper.readValue("""
                {
                  "model": "shen-zhixia",
                  "messages": [
                    {
                      "role": "system",
                      "content": [{"type": "text", "text": "transport prompt"}]
                    },
                    {
                      "role": "user",
                      "content": [
                        {"type": "text", "text": "微信里的消息"},
                        {"type": "image_url", "image_url": {"url": "https://example.invalid/a.jpg"}},
                        {"type": "input_text", "text": "第二段文字"}
                      ]
                    }
                  ],
                  "stream": true
                }
                """, OpenAiChatCompletionsController.ChatCompletionRequest.class);

        var response = controller.complete(
                "Bearer " + TOKEN,
                request
        ).toCompletableFuture().join();

        assertThat(receivedMessage.get()).isEqualTo("微信里的消息\n第二段文字");
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_EVENT_STREAM);
    }

    @Test
    void returnsOpenAiSseChunksForStreamingRequests() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        OpenAiChatCompletionsController controller = controller(
                person,
                receivedMessage
        );

        var response = controller.complete(
                "Bearer " + TOKEN,
                new OpenAiChatCompletionsController.ChatCompletionRequest(
                        MODEL,
                        List.of(new OpenAiChatCompletionsController.ChatMessage(
                                "user",
                                "你好"
                        )),
                        true
                )
        ).toCompletableFuture().join();

        assertThat(receivedMessage.get()).isEqualTo("你好");
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getBody()).isInstanceOf(String.class);
        String body = (String) response.getBody();
        assertThat(body)
                .contains("data: {")
                .contains("\"object\":\"chat.completion.chunk\"")
                .contains("\"model\":\"shen-zhixia\"")
                .contains("\"role\":\"assistant\"")
                .contains("\"content\":\"第一段回复\\n\\n第二段回复\"")
                .contains("\"finish_reason\":\"stop\"")
                .endsWith("data: [DONE]\n\n");
    }

    @Test
    void rejectsInvalidBearerToken() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        OpenAiChatCompletionsController controller = controller(
                person,
                new AtomicReference<>()
        );

        assertThatThrownBy(() -> controller.complete(
                "Bearer wrong-token",
                new OpenAiChatCompletionsController.ChatCompletionRequest(
                        MODEL,
                        List.of(new OpenAiChatCompletionsController.ChatMessage(
                                "user",
                                "你好"
                        )),
                        false
                )
        )).isInstanceOf(InvalidInternalTokenException.class);
    }

    private static OpenAiChatCompletionsController controller(
            Person person,
            AtomicReference<String> receivedMessage
    ) {
        return new OpenAiChatCompletionsController(
                service(person, receivedMessage),
                new PersonApiProperties(false, TOKEN),
                new OpenAiCompatibilityProperties(
                        true,
                        person.getId().toString(),
                        MODEL
                ),
                JsonMapper.builder().build()
        );
    }

    private static PersonDialogueService service(
            Person person,
            AtomicReference<String> receivedMessage
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
                throw new AssertionError("dialogue must not save the person aggregate");
            }
        };
        return new PersonDialogueService(
                repository,
                DefaultPersonModelContextAssembler.withoutExternalSources(),
                (context, message) -> {
                    receivedMessage.set(message);
                    return CompletableFuture.completedFuture(new DialogueResult(
                            "",
                            List.of("第一段回复", "第二段回复")
                    ));
                },
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
