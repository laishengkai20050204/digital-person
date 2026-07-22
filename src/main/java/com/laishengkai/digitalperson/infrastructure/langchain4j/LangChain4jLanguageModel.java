package com.laishengkai.digitalperson.infrastructure.langchain4j;

import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelException;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelMessage;
import com.laishengkai.digitalperson.dialogue.ModelResponseFormat;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.dialogue.ToolResultModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * LangChain4j implementation of the system-owned language-model gateway.
 *
 * <p>The LC4j {@link ChatModel} API is blocking, so each invocation is executed
 * on a virtual thread before being exposed as a {@link CompletionStage}.
 * Request and response bodies are never logged by this adapter.</p>
 */
public final class LangChain4jLanguageModel implements LanguageModelGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            LangChain4jLanguageModel.class
    );
    private static final Executor VIRTUAL_THREAD_EXECUTOR = command ->
            Thread.startVirtualThread(command);
    private static final int MAX_EMPTY_ASSISTANT_ATTEMPTS = 2;

    private final ChatModel chatModel;
    private final String modelName;
    private final String endpointHost;
    private final Executor executor;

    public LangChain4jLanguageModel(LangChain4jModelConfig config) {
        this(
                createChatModel(config),
                Objects.requireNonNull(config, "config cannot be null").modelName(),
                config.baseUrl().getHost(),
                VIRTUAL_THREAD_EXECUTOR
        );
    }

    LangChain4jLanguageModel(
            ChatModel chatModel,
            String modelName,
            String endpointHost
    ) {
        this(chatModel, modelName, endpointHost, Runnable::run);
    }

    LangChain4jLanguageModel(
            ChatModel chatModel,
            String modelName,
            String endpointHost,
            Executor executor
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.modelName = requireText(modelName, "modelName");
        this.endpointHost = requireText(endpointHost, "endpointHost");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");

        LOGGER.info(
                "Configured LangChain4j language model: model={}, endpointHost={}",
                this.modelName,
                this.endpointHost
        );
    }

    @Override
    public CompletionStage<LanguageModelResponse> invoke(
            LanguageModelRequest request
    ) {
        LanguageModelRequest safeRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        CompletableFuture<LanguageModelResponse> result = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                result.complete(invokeBlocking(safeRequest));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private LanguageModelResponse invokeBlocking(LanguageModelRequest request) {
        String invocationId = UUID.randomUUID().toString();
        long startedAtNanos = System.nanoTime();
        ChatRequest chatRequest = toChatRequest(request);

        LOGGER.debug(
                "Starting language model invocation: invocationId={}, model={}, endpointHost={}, messageCount={}, toolCount={}",
                invocationId,
                modelName,
                endpointHost,
                request.messages().size(),
                request.tools().size()
        );

        for (int attempt = 1; attempt <= MAX_EMPTY_ASSISTANT_ATTEMPTS; attempt++) {
            try {
                ChatResponse response = chatModel.chat(chatRequest);
                LanguageModelResponse mappedResponse = toLanguageModelResponse(response);

                LOGGER.debug(
                        "Completed language model invocation: invocationId={}, model={}, attempt={}, responseLength={}, toolCallCount={}, finishReason={}, elapsedMs={}",
                        invocationId,
                        modelName,
                        attempt,
                        mappedResponse.text().length(),
                        mappedResponse.toolCalls().size(),
                        mappedResponse.finishReason(),
                        elapsedMillis(startedAtNanos)
                );

                return mappedResponse;
            } catch (EmptyAssistantMessageException error) {
                if (attempt < MAX_EMPTY_ASSISTANT_ATTEMPTS) {
                    LOGGER.warn(
                            "Retrying empty assistant response: invocationId={}, model={}, endpointHost={}, attempt={}, maxAttempts={}",
                            invocationId,
                            modelName,
                            endpointHost,
                            attempt,
                            MAX_EMPTY_ASSISTANT_ATTEMPTS
                    );
                    continue;
                }

                LanguageModelException failure = new LanguageModelException(
                        "language model returned an empty assistant message after "
                                + MAX_EMPTY_ASSISTANT_ATTEMPTS
                                + " attempts for model "
                                + modelName,
                        error
                );
                logFailure(invocationId, startedAtNanos, failure);
                throw failure;
            } catch (LanguageModelException error) {
                logFailure(invocationId, startedAtNanos, error);
                throw error;
            } catch (RuntimeException error) {
                logFailure(invocationId, startedAtNanos, error);
                throw new LanguageModelException(
                        "language model invocation failed for model " + modelName,
                        error
                );
            }
        }

        throw new IllegalStateException("empty-assistant retry loop completed unexpectedly");
    }

    private static ChatModel createChatModel(LangChain4jModelConfig config) {
        LangChain4jModelConfig safeConfig = Objects.requireNonNull(
                config,
                "config cannot be null"
        );

        return OpenAiChatModel.builder()
                .baseUrl(safeConfig.baseUrl().toString())
                .apiKey(safeConfig.apiKey())
                .modelName(safeConfig.modelName())
                .timeout(safeConfig.timeout())
                .maxRetries(safeConfig.maxRetries())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private static ChatRequest toChatRequest(LanguageModelRequest request) {
        ChatRequest.Builder builder = ChatRequest.builder()
                .messages(request.messages().stream()
                        .map(LangChain4jLanguageModel::toChatMessage)
                        .toList());

        if (request.options().temperature() != null) {
            builder.temperature(request.options().temperature());
        }
        if (request.options().maxOutputTokens() != null) {
            builder.maxOutputTokens(request.options().maxOutputTokens());
        }
        if (!request.options().stopSequences().isEmpty()) {
            builder.stopSequences(request.options().stopSequences());
        }

        builder.responseFormat(toResponseFormat(request.options().responseFormat()));

        if (!request.tools().isEmpty()) {
            builder.toolSpecifications(request.tools().stream()
                    .map(LangChain4jLanguageModel::toToolSpecification)
                    .toList());
            builder.toolChoice(toToolChoice(request.options().toolChoice()));
        }

        return builder.build();
    }

    private static ChatMessage toChatMessage(ModelMessage message) {
        return switch (message) {
            case SystemModelMessage systemMessage ->
                    SystemMessage.from(systemMessage.text());
            case UserModelMessage userMessage ->
                    UserMessage.from(userMessage.text());
            case AssistantModelMessage assistantMessage ->
                    toAiMessage(assistantMessage);
            case ToolResultModelMessage toolResult ->
                    ToolExecutionResultMessage.from(
                            toolResult.toolCallId(),
                            toolResult.toolName(),
                            toolResult.result()
                    );
        };
    }

    private static AiMessage toAiMessage(AssistantModelMessage message) {
        List<ToolExecutionRequest> requests = message.toolCalls().stream()
                .map(call -> {
                    ToolExecutionRequest.Builder builder = ToolExecutionRequest.builder()
                            .name(call.name())
                            .arguments(call.argumentsJson());
                    if (!call.id().isEmpty()) {
                        builder.id(call.id());
                    }
                    return builder.build();
                })
                .toList();

        if (requests.isEmpty()) {
            return AiMessage.from(message.text());
        }
        if (message.text().isEmpty()) {
            return AiMessage.from(requests);
        }
        return AiMessage.from(message.text(), requests);
    }

    private static ToolSpecification toToolSpecification(
            ModelToolSpecification tool
    ) {
        String json = "{\"name\":"
                + quoteJson(tool.name())
                + ",\"description\":"
                + quoteJson(tool.description())
                + ",\"parameters\":"
                + tool.parametersJsonSchema()
                + "}";
        return ToolSpecification.fromJson(json);
    }

    private static ResponseFormat toResponseFormat(ModelResponseFormat format) {
        return switch (format.type()) {
            case TEXT -> ResponseFormat.TEXT;
            case JSON_OBJECT -> ResponseFormat.JSON;
            case JSON_SCHEMA -> ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchema.builder()
                            .name(format.schemaName())
                            .rootElement(JsonRawSchema.from(format.jsonSchema()))
                            .build())
                    .build();
        };
    }

    private static ToolChoice toToolChoice(ModelToolChoice choice) {
        return switch (choice) {
            case AUTO -> ToolChoice.AUTO;
            case NONE -> ToolChoice.NONE;
            case REQUIRED -> ToolChoice.REQUIRED;
        };
    }

    private static LanguageModelResponse toLanguageModelResponse(
            ChatResponse response
    ) {
        if (response == null || response.aiMessage() == null) {
            throw new LanguageModelException(
                    "language model returned no assistant message"
            );
        }

        AiMessage aiMessage = response.aiMessage();
        List<ModelToolCall> toolCalls = aiMessage.toolExecutionRequests() == null
                ? List.of()
                : aiMessage.toolExecutionRequests().stream()
                        .map(request -> new ModelToolCall(
                                request.id(),
                                request.name(),
                                request.arguments()
                        ))
                        .toList();
        String text = Objects.requireNonNullElse(aiMessage.text(), "");

        if (text.isBlank() && toolCalls.isEmpty()) {
            throw new EmptyAssistantMessageException();
        }

        AssistantModelMessage message = new AssistantModelMessage(
                text,
                toolCalls
        );

        return new LanguageModelResponse(
                message,
                toFinishReason(response.finishReason()),
                toUsage(response.tokenUsage())
        );
    }

    private static ModelFinishReason toFinishReason(FinishReason finishReason) {
        if (finishReason == null) {
            return ModelFinishReason.UNKNOWN;
        }
        return switch (finishReason) {
            case STOP -> ModelFinishReason.STOP;
            case LENGTH -> ModelFinishReason.LENGTH;
            case TOOL_EXECUTION -> ModelFinishReason.TOOL_CALLS;
            case CONTENT_FILTER -> ModelFinishReason.CONTENT_FILTER;
            case OTHER -> ModelFinishReason.OTHER;
        };
    }

    private static ModelUsage toUsage(TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return ModelUsage.unknown();
        }
        return new ModelUsage(
                tokenUsage.inputTokenCount(),
                tokenUsage.outputTokenCount(),
                tokenUsage.totalTokenCount()
        );
    }

    private void logFailure(
            String invocationId,
            long startedAtNanos,
            Throwable error
    ) {
        LOGGER.warn(
                "Language model invocation failed: invocationId={}, model={}, endpointHost={}, elapsedMs={}, errorType={}",
                invocationId,
                modelName,
                endpointHost,
                elapsedMillis(startedAtNanos),
                error.getClass().getSimpleName()
        );
    }

    private static String quoteJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }

    private static final class EmptyAssistantMessageException extends RuntimeException {
        private EmptyAssistantMessageException() {
            super("assistant message contained neither text nor tool calls");
        }
    }
}
