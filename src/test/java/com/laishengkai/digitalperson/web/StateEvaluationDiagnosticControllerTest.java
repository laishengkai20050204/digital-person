package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelProperties;
import com.laishengkai.digitalperson.infrastructure.state.StateTransitionEvaluationDiagnostic;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateEvaluationDiagnosticControllerTest {

    @Test
    void shouldListTenProtectedSyntheticScenarios() {
        AtomicInteger invocationCount = new AtomicInteger();
        StateEvaluationDiagnosticController controller = controller(request -> {
            invocationCount.incrementAndGet();
            return CompletableFuture.completedFuture(response("{\"transitions\":[]}"));
        });

        ResponseEntity<?> unauthorized = controller.listScenarios("wrong-token");
        assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.getStatusCode());
        assertEquals(0, invocationCount.get());

        ResponseEntity<?> authorized = controller.listScenarios("expected-token");
        assertEquals(HttpStatus.OK, authorized.getStatusCode());
        StateEvaluationDiagnosticController.ScenarioCatalogResponse body = assertInstanceOf(
                StateEvaluationDiagnosticController.ScenarioCatalogResponse.class,
                authorized.getBody()
        );
        assertEquals(10, body.scenarios().size());
        assertTrue(body.scenarios().stream()
                .anyMatch(scenario -> scenario.id().equals("romantic-reassurance")));
        assertTrue(body.scenarios().stream()
                .anyMatch(scenario -> scenario.id().equals("background-sync-no-effect")));
    }

    @Test
    void shouldReturnExactRequestRawToolArgumentsAndParsedResult() {
        String rawArguments = """
                {"transitions":[
                  {"dimension":"VALENCE","shape":0.6},
                  {"dimension":"TENSION","shape":-0.5},
                  {"dimension":"LONELINESS","shape":-0.7},
                  {"dimension":"SOCIAL_NEED","shape":-0.5}
                ]}
                """.strip();
        StateEvaluationDiagnosticController controller = controller(request ->
                CompletableFuture.completedFuture(response(rawArguments))
        );

        ResponseEntity<?> response = controller.evaluateScenario(
                "romantic-reassurance",
                "expected-token"
        ).toCompletableFuture().join();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        StateEvaluationDiagnosticController.DiagnosticResponse body = assertInstanceOf(
                StateEvaluationDiagnosticController.DiagnosticResponse.class,
                response.getBody()
        );
        assertEquals("UP", body.status());
        assertNotNull(body.request());
        assertTrue(body.request().systemPrompt().contains(
                "Call submit_state_transitions exactly once"
        ));
        assertTrue(body.request().userPrompt().contains(
                "\"title\":\"Romantic partner sends a reassuring affectionate message\""
        ));
        assertEquals("REQUIRED", body.request().toolChoice());
        assertEquals(1, body.request().tools().size());
        assertEquals("submit_state_transitions", body.request().tools().getFirst().name());

        assertNotNull(body.rawResponse());
        assertEquals("TOOL_CALLS", body.rawResponse().finishReason());
        assertEquals(rawArguments, body.rawResponse().toolCalls().getFirst().argumentsJson());
        assertEquals(4, body.parsedTransitions().size());
        assertTrue(body.expectation().passed());
        assertTrue(body.expectation().checks().stream()
                .allMatch(StateEvaluationDiagnosticController.ExpectationCheckResponse::matched));
        assertTrue(body.errorType().isEmpty());
        assertTrue(body.errorMessage().isEmpty());
    }

    @Test
    void shouldExposeExpectationMismatchWithoutDiscardingValidModelOutput() {
        StateEvaluationDiagnosticController controller = controller(request ->
                CompletableFuture.completedFuture(response("""
                        {"transitions":[{"dimension":"VALENCE","shape":-0.2}]}
                        """.strip()))
        );

        ResponseEntity<?> response = controller.evaluateScenario(
                "exam-success",
                "expected-token"
        ).toCompletableFuture().join();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        StateEvaluationDiagnosticController.DiagnosticResponse body = assertInstanceOf(
                StateEvaluationDiagnosticController.DiagnosticResponse.class,
                response.getBody()
        );
        assertEquals("EXPECTATION_MISMATCH", body.status());
        assertFalse(body.expectation().passed());
        assertEquals(1, body.parsedTransitions().size());
        assertEquals(-0.2, body.parsedTransitions().getFirst().shape());
    }

    @Test
    void shouldReturnNotFoundForUnknownScenario() {
        StateEvaluationDiagnosticController controller = controller(request -> {
            throw new AssertionError("gateway should not be called");
        });

        ResponseEntity<?> response = controller.evaluateScenario(
                "does-not-exist",
                "expected-token"
        ).toCompletableFuture().join();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        StateEvaluationDiagnosticController.ErrorResponse body = assertInstanceOf(
                StateEvaluationDiagnosticController.ErrorResponse.class,
                response.getBody()
        );
        assertEquals("UNKNOWN_SCENARIO", body.status());
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

    private static LanguageModelResponse response(String rawArguments) {
        return new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(
                        new ModelToolCall(
                                "call-1",
                                "submit_state_transitions",
                                rawArguments
                        )
                )),
                ModelFinishReason.TOOL_CALLS,
                new ModelUsage(1200, 80, 1280)
        );
    }
}
