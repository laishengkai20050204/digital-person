package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolCallValidatorTest {
    private final AgentToolRegistry registry = AgentToolRegistry.from(List.of(
            tool("lookup")
    ));
    private final AgentToolCallValidator validator = new AgentToolCallValidator(20);

    @Test
    void returnsAnImmutableExtendedCompletedIdSet() {
        Set<String> result = validator.validate(
                List.of(new ModelToolCall("next", "lookup", "{}")),
                registry,
                Set.of("previous")
        );

        assertEquals(Set.of("previous", "next"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add("other"));
    }

    @Test
    void rejectsReusedAndUnavailableCallsBeforeExecution() {
        assertThrows(AgentExecutionException.class, () -> validator.validate(
                List.of(new ModelToolCall("previous", "lookup", "{}")),
                registry,
                Set.of("previous")
        ));
        assertThrows(AgentExecutionException.class, () -> validator.validate(
                List.of(new ModelToolCall("new", "missing", "{}")),
                registry,
                Set.of()
        ));
    }

    @Test
    void rejectsOversizedArguments() {
        assertThrows(AgentExecutionException.class, () -> validator.validate(
                List.of(new ModelToolCall(
                        "new",
                        "lookup",
                        "{\"value\":\"12345678901234567890\"}"
                )),
                registry,
                Set.of()
        ));
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public ModelToolSpecification specification() {
                return new ModelToolSpecification(
                        name,
                        "test tool",
                        "{\"type\":\"object\"}"
                );
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
