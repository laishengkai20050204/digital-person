package com.laishengkai.digitalperson.infrastructure.langchain4j;

import com.laishengkai.digitalperson.dialogue.LanguageModelException;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * LangChain4j implementation of the system-owned language-model gateway.
 *
 * <p>The underlying {@link OpenAiChatModel} can connect to OpenAI-compatible
 * providers by changing {@link LangChain4jModelConfig#baseUrl()} and model name.
 * Request and response bodies are never logged by this adapter.</p>
 */
public final class LangChain4jLanguageModel implements LanguageModelGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            LangChain4jLanguageModel.class
    );

    private final ChatModel chatModel;
    private final String modelName;
    private final String endpointHost;

    public LangChain4jLanguageModel(LangChain4jModelConfig config) {
        this(
                createChatModel(config),
                Objects.requireNonNull(config, "config cannot be null").modelName(),
                config.baseUrl().getHost()
        );
    }

    LangChain4jLanguageModel(
            ChatModel chatModel,
            String modelName,
            String endpointHost
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.modelName = requireText(modelName, "modelName");
        this.endpointHost = requireText(endpointHost, "endpointHost");

        LOGGER.info(
                "Configured LangChain4j language model: model={}, endpointHost={}",
                this.modelName,
                this.endpointHost
        );
    }

    @Override
    public LanguageModelResponse generate(LanguageModelRequest request) {
        LanguageModelRequest safeRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        String invocationId = UUID.randomUUID().toString();
        long startedAtNanos = System.nanoTime();

        LOGGER.debug(
                "Starting language model invocation: invocationId={}, model={}, endpointHost={}, systemMessageLength={}, userMessageLength={}",
                invocationId,
                modelName,
                endpointHost,
                safeRequest.systemMessage().length(),
                safeRequest.userMessage().length()
        );

        try {
            ChatResponse response = chatModel.chat(toChatRequest(safeRequest));
            if (response == null || response.aiMessage() == null) {
                throw new LanguageModelException("language model returned no assistant message");
            }

            String text = response.aiMessage().text();
            if (text == null) {
                throw new LanguageModelException("language model returned no text");
            }

            LOGGER.debug(
                    "Completed language model invocation: invocationId={}, model={}, responseLength={}, elapsedMs={}",
                    invocationId,
                    modelName,
                    text.length(),
                    elapsedMillis(startedAtNanos)
            );

            return new LanguageModelResponse(text);
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
        List<ChatMessage> messages = new ArrayList<>(2);
        if (!request.systemMessage().isEmpty()) {
            messages.add(SystemMessage.from(request.systemMessage()));
        }
        messages.add(UserMessage.from(request.userMessage()));

        return ChatRequest.builder()
                .messages(List.copyOf(messages))
                .build();
    }

    private void logFailure(
            String invocationId,
            long startedAtNanos,
            RuntimeException error
    ) {
        LOGGER.warn(
                "Language model invocation failed: invocationId={}, model={}, endpointHost={}, elapsedMs={}",
                invocationId,
                modelName,
                endpointHost,
                elapsedMillis(startedAtNanos),
                error
        );
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
}
