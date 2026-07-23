package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelException;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelProperties;
import com.laishengkai.digitalperson.infrastructure.state.StateTransitionEvaluationDiagnostic;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateEvaluationReliabilityTest {

    @Test
    void shouldUseChineseConservativePromptAndIsoTimestamps() {
        AtomicReference<LanguageModelRequest> captured = new AtomicReference<>();
        StateEvaluationDiagnosticController controller = controller(request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(emptyResponse());
        });

        ResponseEntity<?> response = controller.evaluateScenario(
                "background-sync-no-effect",
                "expected-token"
        ).toCompletableFuture().join();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        LanguageModelRequest request = captured.get();
        SystemModelMessage systemMessage = assertInstanceOf(
                SystemModelMessage.class,
                request.messages().getFirst()
        );
        UserModelMessage userMessage = assertInstanceOf(
                UserModelMessage.class,
                request.messages().get(1)
        );
        String systemPrompt = systemMessage.text();
        String userPrompt = userMessage.text();

        assertTrue(systemPrompt.contains("只提交由 newEvent 直接导致"));
        assertTrue(systemPrompt.contains("证据较弱时应直接省略"));
        assertTrue(systemPrompt.contains("aftermathTransitions"));
        assertTrue(userPrompt.contains("\"evaluationTime\":\""));
        assertTrue(userPrompt.contains("\"startTime\":\""));
        assertFalse(userPrompt.matches(".*\"evaluationTime\":\\d+.*"));
    }

    @Test
    void shouldExposeRootCauseThroughExistingErrorMessage() {
        StateEvaluationDiagnosticController controller = controller(request ->
                CompletableFuture.failedFuture(new LanguageModelException(
                        "language model invocation failed",
                        new IllegalArgumentException("invalid provider response")
                ))
        );

        ResponseEntity<?> response = controller.evaluateScenario(
                "friend-conflict",
                "expected-token"
        ).toCompletableFuture().join();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        StateEvaluationDiagnosticController.DiagnosticResponse body = assertInstanceOf(
                StateEvaluationDiagnosticController.DiagnosticResponse.class,
                response.getBody()
        );
        assertEquals("LanguageModelException", body.errorType());
        assertTrue(body.errorMessage().contains("rootCause=IllegalArgumentException"));
        assertTrue(body.errorMessage().contains("invalid provider response"));
    }

    private static StateEvaluationDiagnosticController controller(
            com.laishengkai.digitalperson.dialogue.LanguageModelGateway gateway
    ) {
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
        return new StateEvaluationDiagnosticController(
                new StateTransitionEvaluationDiagnostic(gateway),
                properties
        );
    }

    private static LanguageModelResponse emptyResponse() {
        return new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(
                        new ModelToolCall(
                                "call-1",
                                "submit_state_transitions",
                                """
                                        {
                                          "activeTransitions":[],
                                          "aftermathTransitions":[],
                                          "aftermathDurationMinutes":0
                                        }
                                        """.strip()
                        )
                )),
                ModelFinishReason.TOOL_CALLS,
                new ModelUsage(1, 1, 2)
        );
    }
}
