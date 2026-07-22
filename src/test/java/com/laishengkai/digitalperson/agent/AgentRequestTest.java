package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.ModelInvocationOptions;
import com.laishengkai.digitalperson.dialogue.ModelResponseFormat;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRequestTest {

    @Test
    void shouldRejectDuplicateExecutableToolNames() {
        AgentTool first = tool("lookup");
        AgentTool second = tool("lookup");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRequest(
                        List.of(new UserModelMessage("hello")),
                        ModelInvocationOptions.defaults(),
                        List.of(first, second),
                        4
                )
        );

        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void shouldRequireExecutableToolWhenToolChoiceIsRequired() {
        ModelInvocationOptions options = new ModelInvocationOptions(
                null,
                null,
                List.of(),
                ModelToolChoice.REQUIRED,
                ModelResponseFormat.text()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRequest(
                        List.of(new UserModelMessage("hello")),
                        options,
                        List.of(),
                        4
                )
        );

        assertTrue(error.getMessage().contains("REQUIRED"));
    }

    @Test
    void shouldRejectNonPositiveInvocationLimit() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRequest(
                        List.of(new UserModelMessage("hello")),
                        ModelInvocationOptions.defaults(),
                        List.of(),
                        0
                )
        );

        assertTrue(error.getMessage().contains("positive"));
    }

    private static AgentTool tool(String name) {
        ModelToolSpecification specification = new ModelToolSpecification(
                name,
                "test tool",
                "{\"type\":\"object\"}"
        );
        return new AgentTool() {
            @Override
            public ModelToolSpecification specification() {
                return specification;
            }

            @Override
            public java.util.concurrent.CompletionStage<String> execute(
                    String argumentsJson
            ) {
                return CompletableFuture.completedFuture("ok");
            }
        };
    }
}
