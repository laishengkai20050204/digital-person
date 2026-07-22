package com.laishengkai.digitalperson.dialogue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageModelContractTest {

    @Test
    void shouldRepresentToolRequestsInsideAssistantMessage() {
        ModelToolCall toolCall = new ModelToolCall(
                "call-1",
                "search_web",
                "{\"query\":\"latest AI news\"}"
        );

        AssistantModelMessage message = new AssistantModelMessage(
                "I will check.",
                List.of(toolCall)
        );

        assertEquals("I will check.", message.text());
        assertEquals(List.of(toolCall), message.toolCalls());
        assertTrue(message instanceof ModelMessage);
    }

    @Test
    void shouldDefensivelyCopyCompleteInvocationContext() {
        List<ModelMessage> messages = new ArrayList<>(List.of(
                new SystemModelMessage("system"),
                new UserModelMessage("hello")
        ));
        List<ModelToolSpecification> tools = new ArrayList<>(List.of(
                new ModelToolSpecification(
                        "search_web",
                        "Search current web information",
                        "{\"type\":\"object\",\"properties\":{}}"
                )
        ));

        LanguageModelRequest request = new LanguageModelRequest(
                messages,
                ModelInvocationOptions.defaults(),
                tools
        );
        messages.clear();
        tools.clear();

        assertEquals(2, request.messages().size());
        assertEquals(1, request.tools().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.messages().add(new UserModelMessage("later"))
        );
    }

    @Test
    void shouldRequireVisibleToolWhenToolChoiceIsRequired() {
        ModelInvocationOptions options = new ModelInvocationOptions(
                null,
                null,
                List.of(),
                ModelToolChoice.REQUIRED,
                ModelResponseFormat.text()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new LanguageModelRequest(
                        List.of(new UserModelMessage("hello")),
                        options,
                        List.of()
                )
        );
    }
}
