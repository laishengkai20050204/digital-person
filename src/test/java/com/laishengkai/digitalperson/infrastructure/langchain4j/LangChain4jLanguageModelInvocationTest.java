package com.laishengkai.digitalperson.infrastructure.langchain4j;

import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelInvocationOptions;
import com.laishengkai.digitalperson.dialogue.ModelResponseFormat;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.dialogue.ToolResultModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LangChain4jLanguageModelInvocationTest {

    @Test
    void shouldMapHistoryToolsAndToolRequestResponse() {
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                capturedRequest.set(request);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(
                                ToolExecutionRequest.builder()
                                        .id("call-2")
                                        .name("search_web")
                                        .arguments("{\"query\":\"today\"}")
                                        .build()
                        )))
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .tokenUsage(new TokenUsage(20, 5, 25))
                        .build();
            }
        };

        LangChain4jLanguageModel gateway = new LangChain4jLanguageModel(
                chatModel,
                "test-model",
                "example.com",
                Runnable::run
        );

        LanguageModelRequest request = new LanguageModelRequest(
                List.of(
                        new SystemModelMessage("system"),
                        new UserModelMessage("question"),
                        new AssistantModelMessage(
                                "",
                                List.of(new ModelToolCall(
                                        "call-1",
                                        "search_web",
                                        "{\"query\":\"yesterday\"}"
                                ))
                        ),
                        new ToolResultModelMessage(
                                "call-1",
                                "search_web",
                                "result"
                        )
                ),
                new ModelInvocationOptions(
                        0.2,
                        100,
                        List.of("STOP"),
                        ModelToolChoice.AUTO,
                        ModelResponseFormat.jsonObject()
                ),
                List.of(new ModelToolSpecification(
                        "search_web",
                        "Search current information",
                        "{\"type\":\"object\",\"properties\":{}}"
                ))
        );

        LanguageModelResponse response = gateway.invoke(request)
                .toCompletableFuture()
                .join();

        ChatRequest mappedRequest = capturedRequest.get();
        assertNotNull(mappedRequest);
        assertEquals(4, mappedRequest.messages().size());
        assertEquals(1, mappedRequest.toolSpecifications().size());
        assertEquals(0.2, mappedRequest.temperature());
        assertEquals(100, mappedRequest.maxOutputTokens());
        assertEquals(List.of("STOP"), mappedRequest.stopSequences());

        assertEquals(ModelFinishReason.TOOL_CALLS, response.finishReason());
        assertEquals(1, response.toolCalls().size());
        assertEquals("call-2", response.toolCalls().getFirst().id());
        assertEquals("search_web", response.toolCalls().getFirst().name());
        assertEquals(20, response.usage().inputTokens());
        assertEquals(5, response.usage().outputTokens());
        assertEquals(25, response.usage().totalTokens());
    }
}
