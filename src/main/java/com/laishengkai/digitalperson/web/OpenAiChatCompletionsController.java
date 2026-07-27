package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonDialogueExchange;
import com.laishengkai.digitalperson.application.PersonDialogueService;
import com.laishengkai.digitalperson.person.PersonId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Minimal OpenAI Chat Completions adapter for OpenClaw's WeChat channel. */
@RestController
@RequestMapping("/v1/chat/completions")
@ConditionalOnProperty(
        prefix = "digital-person.openai-compat",
        name = "enabled",
        havingValue = "true"
)
public final class OpenAiChatCompletionsController {
    private static final String BEARER_PREFIX = "Bearer ";

    private final PersonDialogueService dialogueService;
    private final InternalTokenGuard tokenGuard;
    private final PersonId personId;
    private final String configuredModel;

    public OpenAiChatCompletionsController(
            PersonDialogueService dialogueService,
            PersonApiProperties personApiProperties,
            OpenAiCompatibilityProperties compatibilityProperties
    ) {
        this.dialogueService = Objects.requireNonNull(
                dialogueService,
                "dialogueService cannot be null"
        );
        this.tokenGuard = new InternalTokenGuard(Objects.requireNonNull(
                personApiProperties,
                "personApiProperties cannot be null"
        ).requiredToken());
        OpenAiCompatibilityProperties properties = Objects.requireNonNull(
                compatibilityProperties,
                "compatibilityProperties cannot be null"
        );
        this.personId = properties.requiredPersonId();
        this.configuredModel = properties.requiredModel();
    }

    @PostMapping
    public CompletionStage<ResponseEntity<ChatCompletionResponse>> complete(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @RequestBody ChatCompletionRequest request
    ) {
        tokenGuard.requireAuthorized(extractBearerToken(authorization));
        ChatCompletionRequest requested = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        if (Boolean.TRUE.equals(requested.stream())) {
            throw new IllegalArgumentException("streaming is not supported");
        }

        String requestedModel = requireText(requested.model(), "model");
        if (!configuredModel.equals(requestedModel)) {
            throw new IllegalArgumentException("requested model is not configured");
        }
        String userMessage = lastUserMessage(requested.messages());

        return dialogueService.dialogue(personId, userMessage)
                .thenApply(exchange -> ResponseEntity.ok(toResponse(exchange)));
    }

    private ChatCompletionResponse toResponse(PersonDialogueExchange exchange) {
        String content = String.join("\n\n", exchange.result().replies());
        return new ChatCompletionResponse(
                "chatcmpl-" + UUID.randomUUID(),
                "chat.completion",
                exchange.occurredAt().getEpochSecond(),
                configuredModel,
                List.of(new Choice(
                        0,
                        new AssistantMessage("assistant", content),
                        "stop"
                )),
                new Usage(0, 0, 0)
        );
    }

    private static String lastUserMessage(List<ChatMessage> messages) {
        List<ChatMessage> safeMessages = List.copyOf(Objects.requireNonNull(
                messages,
                "messages cannot be null"
        ));
        for (int index = safeMessages.size() - 1; index >= 0; index--) {
            ChatMessage message = Objects.requireNonNull(
                    safeMessages.get(index),
                    "messages cannot contain null"
            );
            if (message.role() != null && "user".equalsIgnoreCase(message.role().strip())) {
                return requireText(message.content(), "user message content");
            }
        }
        throw new IllegalArgumentException("messages must contain a user message");
    }

    private static String extractBearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.strip();
        if (value.length() <= BEARER_PREFIX.length()
                || !value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = value.substring(BEARER_PREFIX.length()).strip();
        return token.isEmpty() ? null : token;
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name + " cannot be null").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }

    public record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            Boolean stream
    ) {
    }

    public record ChatMessage(String role, String content) {
    }

    public record ChatCompletionResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage
    ) {
    }

    public record Choice(
            int index,
            AssistantMessage message,
            String finish_reason
    ) {
    }

    public record AssistantMessage(String role, String content) {
    }

    public record Usage(
            int prompt_tokens,
            int completion_tokens,
            int total_tokens
    ) {
    }
}
