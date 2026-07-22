package com.laishengkai.digitalperson.infrastructure.langchain4j;

import com.laishengkai.digitalperson.dialogue.LanguageModelException;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChain4jLanguageModelEmptyAssistantRetryTest {

    @Test
    void shouldRetryOneEmptyAssistantResponseAndReturnTheSecondResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(emptyResponse(), textResponse("recovered"));

        LangChain4jLanguageModel model = new LangChain4jLanguageModel(
                chatModel,
                "provider/model",
                "openrouter.ai"
        );

        LanguageModelResponse response = model.invoke(
                        LanguageModelRequest.userMessage("hello")
                )
                .toCompletableFuture()
                .join();

        assertEquals("recovered", response.text());
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldFailAfterTwoConsecutiveEmptyAssistantResponses() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(emptyResponse(), emptyResponse());

        LangChain4jLanguageModel model = new LangChain4jLanguageModel(
                chatModel,
                "provider/model",
                "openrouter.ai"
        );

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
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldNotRetryUnrelatedProviderFailures() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class)))
                .thenThrow(new IllegalStateException("provider failure"));

        LangChain4jLanguageModel model = new LangChain4jLanguageModel(
                chatModel,
                "provider/model",
                "openrouter.ai"
        );

        CompletionException error = assertThrows(
                CompletionException.class,
                () -> model.invoke(LanguageModelRequest.userMessage("hello"))
                        .toCompletableFuture()
                        .join()
        );
        assertInstanceOf(LanguageModelException.class, error.getCause());
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    private static ChatResponse emptyResponse() {
        AiMessage message = mock(AiMessage.class);
        when(message.text()).thenReturn("");
        when(message.toolExecutionRequests()).thenReturn(List.of());

        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(message);
        when(response.finishReason()).thenReturn(FinishReason.STOP);
        return response;
    }

    private static ChatResponse textResponse(String text) {
        AiMessage message = mock(AiMessage.class);
        when(message.text()).thenReturn(text);
        when(message.toolExecutionRequests()).thenReturn(List.of());

        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(message);
        when(response.finishReason()).thenReturn(FinishReason.STOP);
        return response;
    }
}
