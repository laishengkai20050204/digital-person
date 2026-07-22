package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.agent.AgentExecutor;
import com.laishengkai.digitalperson.agent.AgentResult;
import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentToolLoopTestControllerTest {

    @Test
    void shouldRejectIncorrectTokenWithoutExecutingAgent() {
        AgentExecutor executor = request -> {
            throw new AssertionError("agent should not be executed");
        };
        AgentToolLoopTestController controller = controller(executor);

        ResponseEntity<AgentToolLoopTestController.ToolLoopTestResponse> response =
                controller.testToolLoop("wrong-token").toCompletableFuture().join();

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UNAUTHORIZED", response.getBody().status());
    }

    @Test
    void shouldReturnUpOnlyAfterOneRealToolExecution() {
        AgentExecutor executor = request -> CompletableFuture.completedFuture(
                result("AGENT_TOOL_OK", 2, 1)
        );
        AgentToolLoopTestController controller = controller(executor);

        ResponseEntity<AgentToolLoopTestController.ToolLoopTestResponse> response =
                controller.testToolLoop("expected-token").toCompletableFuture().join();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().status());
        assertEquals("AGENT_TOOL_OK", response.getBody().modelResponse());
        assertEquals(2, response.getBody().modelInvocationCount());
        assertEquals(1, response.getBody().toolExecutionCount());
    }

    @Test
    void shouldRejectFinalTextWhenModelSkippedTheTool() {
        AgentExecutor executor = request -> CompletableFuture.completedFuture(
                result("AGENT_TOOL_OK", 1, 0)
        );
        AgentToolLoopTestController controller = controller(executor);

        ResponseEntity<AgentToolLoopTestController.ToolLoopTestResponse> response =
                controller.testToolLoop("expected-token").toCompletableFuture().join();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UNEXPECTED_TOOL_COUNT", response.getBody().status());
        assertEquals(0, response.getBody().toolExecutionCount());
    }

    @Test
    void shouldRejectUnexpectedFinalModelResponse() {
        AgentExecutor executor = request -> CompletableFuture.completedFuture(
                result("wrong", 2, 1)
        );
        AgentToolLoopTestController controller = controller(executor);

        ResponseEntity<AgentToolLoopTestController.ToolLoopTestResponse> response =
                controller.testToolLoop("expected-token").toCompletableFuture().join();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UNEXPECTED_RESPONSE", response.getBody().status());
    }

    @Test
    void shouldReturnBadGatewayWhenAgentFails() {
        AgentExecutor executor = request -> CompletableFuture.failedFuture(
                new IllegalStateException("provider unavailable")
        );
        AgentToolLoopTestController controller = controller(executor);

        ResponseEntity<AgentToolLoopTestController.ToolLoopTestResponse> response =
                controller.testToolLoop("expected-token").toCompletableFuture().join();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().status());
    }

    private static AgentResult result(
            String text,
            int modelInvocationCount,
            int toolExecutionCount
    ) {
        LanguageModelResponse finalResponse = LanguageModelResponse.text(text);
        return new AgentResult(
                finalResponse,
                List.of(AssistantModelMessage.text(text)),
                modelInvocationCount,
                toolExecutionCount,
                ModelUsage.unknown()
        );
    }

    private static AgentToolLoopTestController controller(AgentExecutor executor) {
        LanguageModelProperties properties = new LanguageModelProperties(
                true,
                URI.create("https://openrouter.ai/api/v1"),
                "test-key",
                "provider/model",
                Duration.ofSeconds(5),
                0,
                new LanguageModelProperties.ConnectionTest(
                        true,
                        "expected-token"
                )
        );
        return new AgentToolLoopTestController(executor, properties);
    }
}
