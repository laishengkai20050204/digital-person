package com.laishengkai.digitalperson.infrastructure.langchain4j;

import com.laishengkai.digitalperson.dialogue.LanguageModelException;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jLanguageModelEmptyAssistantRetryTest {

    @Test
    void shouldRetryOneEmptyAssistantResponseAndReturnTheSecondResponse() {
        SequenceChatModel chatModel = new SequenceChatModel(
                emptyResponse(),
                textResponse("recovered")
        );
        LangChain4jLanguageModel model = adapter(chatModel);

        LanguageModelResponse response = model.invoke(
                        LanguageModelRequest.userMessage("hello")
                )
                .toCompletableFuture()
                .join();

        assertEquals("recovered", response.text());
        assertEquals(2, chatModel.invocationCount());
    }

    @Test
    void shouldFailAfterTwoConsecutiveEmptyAssistantResponses() {
        SequenceChatModel chatModel = new SequenceChatModel(
                emptyResponse(),
                emptyResponse()
        );
        LangChain4jLanguageModel model = adapter(chatModel);

        CompletionException error = assertThrows(
                CompletionException.class,
                () -> model.invoke(LanguageModelRequest.userMessage("hello"))
                        .toCompletableFuture()
                        .join()
        );
        LanguageModelException failure = assertInstanceOf(
                LanguageModelException.class,
                error.getCause()
        );

        assertTrue(failure.getMessage().contains("after 2 attempts"));
        assertTrue(failure.getMessage().contains("provider/model"));
        assertEquals(2, chatModel.invocationCount());
    }

    @Test
    void shouldNotRetryUnrelatedProviderFailures() {
        SequenceChatModel chatModel = new SequenceChatModel(
                new IllegalStateException("provider failure")
        );
        LangChain4jLanguageModel model = adapter(chatModel);

        CompletionException error = assertThrows(
                CompletionException.class,
                () -> model.invoke(LanguageModelRequest.userMessage("hello"))
                        .toCompletableFuture()
                        .join()
        );

        assertInstanceOf(LanguageModelException.class, error.getCause());
        assertEquals(1, chatModel.invocationCount());
    }

    private static LangChain4jLanguageModel adapter(ChatModel chatModel) {
        return new LangChain4jLanguageModel(
                chatModel,
                "provider/model",
                "openrouter.ai"
        );
    }

    private static ChatResponse emptyResponse() {
        return ChatResponse.builder()
                .aiMessage(new EmptyAiMessage())
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static final class EmptyAiMessage extends AiMessage {
        private EmptyAiMessage() {
            super("placeholder");
        }

        @Override
        public String text() {
            return "";
        }

        @Override
        public List<ToolExecutionRequest> toolExecutionRequests() {
            return List.of();
        }
    }

    private static final class SequenceChatModel implements ChatModel {
        private final List<Object> outcomes;
        private int invocationCount;

        private SequenceChatModel(Object... outcomes) {
            this.outcomes = List.of(outcomes);
        }

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            Object outcome = outcomes.get(invocationCount++);
            if (outcome instanceof RuntimeException error) {
                throw error;
            }
            return (ChatResponse) outcome;
        }

        private int invocationCount() {
            return invocationCount;
        }
    }
}
