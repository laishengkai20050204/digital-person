package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.DialogueActivityReactionService;
import com.laishengkai.digitalperson.application.PersonDialogueExchange;
import com.laishengkai.digitalperson.application.PersonDialogueService;
import com.laishengkai.digitalperson.person.PersonId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

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
    private static final String OPENCLAW_INTERNAL_CONTEXT_PREFIX =
            "OpenClaw runtime context for the immediately preceding user message.";
    private static final String OPENCLAW_INTERNAL_CONTEXT_MARKER =
            "<<<BEGIN_OPENCLAW_INTERNAL_CONTEXT>>>";
    private static final Pattern OPENCLAW_MESSAGE_TIMESTAMP = Pattern.compile(
            "^\\[(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun) "
                    + "\\d{4}-\\d{2}-\\d{2} "
                    + "\\d{2}:\\d{2}(?::\\d{2})? "
                    + "(?:GMT|UTC)[+-]\\d{1,2}(?::\\d{2})?\\]\\s*"
    );

    private final PersonDialogueService dialogueService;
    private final WechatSlashCommandHandler slashCommandHandler;
    private final DialogueActivityReactionService activityReactionService;
    private final InternalTokenGuard tokenGuard;
    private final PersonId personId;
    private final String configuredModel;
    private final JsonMapper jsonMapper;

    @Autowired
    public OpenAiChatCompletionsController(
            PersonDialogueService dialogueService,
            WechatSlashCommandHandler slashCommandHandler,
            PersonApiProperties personApiProperties,
            OpenAiCompatibilityProperties compatibilityProperties,
            JsonMapper jsonMapper,
            ObjectProvider<DialogueActivityReactionService> activityReactionProvider
    ) {
        this(
                dialogueService,
                slashCommandHandler,
                personApiProperties,
                compatibilityProperties,
                jsonMapper,
                Objects.requireNonNull(
                        activityReactionProvider,
                        "activityReactionProvider cannot be null"
                ).getIfAvailable()
        );
    }

    public OpenAiChatCompletionsController(
            PersonDialogueService dialogueService,
            WechatSlashCommandHandler slashCommandHandler,
            PersonApiProperties personApiProperties,
            OpenAiCompatibilityProperties compatibilityProperties,
            JsonMapper jsonMapper
    ) {
        this(
                dialogueService,
                slashCommandHandler,
                personApiProperties,
                compatibilityProperties,
                jsonMapper,
                (DialogueActivityReactionService) null
        );
    }

    private OpenAiChatCompletionsController(
            PersonDialogueService dialogueService,
            WechatSlashCommandHandler slashCommandHandler,
            PersonApiProperties personApiProperties,
            OpenAiCompatibilityProperties compatibilityProperties,
            JsonMapper jsonMapper,
            DialogueActivityReactionService activityReactionService
    ) {
        this.dialogueService = Objects.requireNonNull(
                dialogueService,
                "dialogueService cannot be null"
        );
        this.slashCommandHandler = Objects.requireNonNull(
                slashCommandHandler,
                "slashCommandHandler cannot be null"
        );
        this.activityReactionService = activityReactionService;
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
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper cannot be null");
    }

    OpenAiChatCompletionsController(
            PersonDialogueService dialogueService,
            PersonApiProperties personApiProperties,
            OpenAiCompatibilityProperties compatibilityProperties,
            JsonMapper jsonMapper
    ) {
        this(
                dialogueService,
                (requestedPersonId, message) -> Optional.empty(),
                personApiProperties,
                compatibilityProperties,
                jsonMapper
        );
    }

    @PostMapping
    public CompletionStage<ResponseEntity<?>> complete(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @RequestBody ChatCompletionRequest request
    ) {
        tokenGuard.requireAuthorized(extractBearerToken(authorization));
        ChatCompletionRequest requested = Objects.requireNonNull(
                request,
                "request cannot be null"
        );

        String requestedModel = requireText(requested.model(), "model");
        if (!configuredModel.equals(requestedModel)) {
            throw new IllegalArgumentException("requested model is not configured");
        }
        String userMessage = lastUserMessage(requested.messages());
        boolean streaming = Boolean.TRUE.equals(requested.stream());

        Optional<WechatSlashCommandHandler.CommandResult> commandResult =
                slashCommandHandler.handle(personId, userMessage);
        if (commandResult.isPresent()) {
            WechatSlashCommandHandler.CommandResult result = commandResult.orElseThrow();
            return CompletableFuture.completedFuture(response(
                    result.content(),
                    result.occurredAt(),
                    streaming
            ));
        }

        return dialogueService.dialogue(personId, userMessage)
                .thenApply(exchange -> {
                    triggerActivityReaction(userMessage, exchange);
                    return response(exchange, streaming);
                });
    }

    private void triggerActivityReaction(String userMessage, PersonDialogueExchange exchange) {
        if (activityReactionService != null) {
            activityReactionService.triggerIfNeeded(userMessage, exchange);
        }
    }

    private ResponseEntity<?> response(PersonDialogueExchange exchange, boolean streaming) {
        String content = String.join("\n\n", exchange.result().replies());
        return response(content, exchange.occurredAt(), streaming);
    }

    private ResponseEntity<?> response(String content, Instant occurredAt, boolean streaming) {
        return streaming
                ? streamingResponse(content, occurredAt)
                : ResponseEntity.ok(nonStreamingResponse(content, occurredAt));
    }

    private ChatCompletionResponse nonStreamingResponse(String content, Instant occurredAt) {
        return new ChatCompletionResponse(
                completionId(),
                "chat.completion",
                occurredAt.getEpochSecond(),
                configuredModel,
                List.of(new Choice(
                        0,
                        new AssistantMessage("assistant", content),
                        "stop"
                )),
                new Usage(0, 0, 0)
        );
    }

    private ResponseEntity<String> streamingResponse(String content, Instant occurredAt) {
        String id = completionId();
        long created = occurredAt.getEpochSecond();

        ChatCompletionChunk contentChunk = new ChatCompletionChunk(
                id,
                "chat.completion.chunk",
                created,
                configuredModel,
                List.of(new ChunkChoice(
                        0,
                        Map.of("role", "assistant", "content", content),
                        null
                ))
        );
        ChatCompletionChunk stopChunk = new ChatCompletionChunk(
                id,
                "chat.completion.chunk",
                created,
                configuredModel,
                List.of(new ChunkChoice(0, Map.of(), "stop"))
        );

        String body = "data: " + writeJson(contentChunk) + "\n\n"
                + "data: " + writeJson(stopChunk) + "\n\n"
                + "data: [DONE]\n\n";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("could not serialize OpenAI streaming response", error);
        }
    }

    private static String completionId() {
        return "chatcmpl-" + UUID.randomUUID();
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
            if (message.role() == null || !"user".equalsIgnoreCase(message.role().strip())) {
                continue;
            }
            String candidate = userText(message.content());
            if (isOpenClawInternalContext(candidate)) {
                continue;
            }
            return normalizeOpenClawUserMessage(candidate);
        }
        throw new IllegalArgumentException(
                "messages must contain a non-internal user message"
        );
    }

    private static boolean isOpenClawInternalContext(String text) {
        String normalized = text.strip();
        return normalized.startsWith(OPENCLAW_INTERNAL_CONTEXT_PREFIX)
                || normalized.contains(OPENCLAW_INTERNAL_CONTEXT_MARKER);
    }

    private static String normalizeOpenClawUserMessage(String text) {
        String normalized = OPENCLAW_MESSAGE_TIMESTAMP
                .matcher(text.strip())
                .replaceFirst("")
                .strip();
        return requireText(normalized, "user message content");
    }

    private static String userText(Object content) {
        if (content instanceof String text) {
            return requireText(text, "user message content");
        }
        if (content instanceof List<?> parts) {
            StringBuilder combined = new StringBuilder();
            for (Object part : parts) {
                appendTextPart(combined, part);
            }
            return requireText(combined.toString(), "user message content");
        }
        throw new IllegalArgumentException(
                "user message content must be text or an array of text parts"
        );
    }

    private static void appendTextPart(StringBuilder combined, Object part) {
        if (part instanceof String text) {
            appendText(combined, text);
            return;
        }
        if (!(part instanceof Map<?, ?> values)) {
            return;
        }
        Object type = values.get("type");
        if (!isSupportedTextPart(type)) {
            return;
        }
        Object text = values.get("text");
        if (text instanceof String value) {
            appendText(combined, value);
        }
    }

    private static boolean isSupportedTextPart(Object type) {
        if (type == null) {
            return true;
        }
        if (!(type instanceof String value)) {
            return false;
        }
        String normalized = value.strip();
        return "text".equalsIgnoreCase(normalized)
                || "input_text".equalsIgnoreCase(normalized);
    }

    private static void appendText(StringBuilder combined, String text) {
        String normalized = text == null ? "" : text.strip();
        if (normalized.isEmpty()) {
            return;
        }
        if (!combined.isEmpty()) {
            combined.append('\n');
        }
        combined.append(normalized);
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

    public record ChatMessage(String role, Object content) {
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

    public record ChatCompletionChunk(
            String id,
            String object,
            long created,
            String model,
            List<ChunkChoice> choices
    ) {
    }

    public record ChunkChoice(
            int index,
            Map<String, String> delta,
            String finish_reason
    ) {
    }
}
