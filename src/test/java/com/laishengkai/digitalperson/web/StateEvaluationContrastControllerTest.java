package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateEvaluationContrastControllerTest {

    @Test
    void shouldListThreeProtectedContrastGroups() {
        StateEvaluationContrastController controller = controller(request ->
                CompletableFuture.completedFuture(response(emptyImpact()))
        );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                controller.listGroups("wrong-token").getStatusCode()
        );

        ResponseEntity<?> response = controller.listGroups("expected-token");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        StateEvaluationContrastController.GroupCatalogResponse body = assertInstanceOf(
                StateEvaluationContrastController.GroupCatalogResponse.class,
                response.getBody()
        );
        assertEquals(3, body.groups().size());
        assertTrue(body.groups().stream().allMatch(group -> group.scenarioIds().size() == 2));
        assertTrue(body.groups().stream().anyMatch(group ->
                group.id().equals("conflict-by-emotionality")
        ));
    }

    @Test
    void shouldRunChineseControlledScenarioThroughProductionProtocol() {
        AtomicReference<LanguageModelRequest> captured = new AtomicReference<>();
        StateEvaluationContrastController controller = controller(request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(response("""
                    {"effects":[{
                      "type":"EMOTIONAL",
                      "cause":"亲密朋友的严厉指责引发受伤和紧张",
                      "transitions":[
                        {"dimension":"TENSION","shape":0.50},
                        {"dimension":"VALENCE","shape":-0.45}
                      ],
                      "endPolicy":"FIXED_TIME",
                      "durationMinutes":240
                    }]}
                    """.strip()));
        });

        ResponseEntity<?> response = controller.evaluateScenario(
                "friend-conflict-high-emotionality",
                "expected-token"
        ).toCompletableFuture().join();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        StateEvaluationContrastController.ContrastResponse body = assertInstanceOf(
                StateEvaluationContrastController.ContrastResponse.class,
                response.getBody()
        );
        assertEquals("UP", body.status());
        assertEquals("高情绪性", body.scenario().variant());
        assertEquals(2, body.parsedTransitions().size());
        assertEquals("", body.errorType());

        UserModelMessage userMessage = assertInstanceOf(
                UserModelMessage.class,
                captured.get().messages().get(1)
        );
        String userPrompt = userMessage.text();
        assertTrue(userPrompt.contains("亲密朋友因误会发来严厉指责"));
        assertTrue(userPrompt.contains("\"emotionality\":0.9"));
        assertTrue(userPrompt.contains("\"evaluationTime\":\"2026-07-22T20:00:00Z\""));
    }

    @Test
    void shouldReturnNotFoundForUnknownContrastScenario() {
        StateEvaluationContrastController controller = controller(request -> {
            throw new AssertionError("gateway should not be called");
        });

        ResponseEntity<?> response = controller.evaluateScenario(
                "missing",
                "expected-token"
        ).toCompletableFuture().join();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    private static StateEvaluationContrastController controller(
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
        return new StateEvaluationContrastController(
                new StateTransitionEvaluationDiagnostic(gateway),
                properties
        );
    }

    private static LanguageModelResponse response(String argumentsJson) {
        return new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(new ModelToolCall(
                        "call-1",
                        "submit_state_effects",
                        argumentsJson
                ))),
                ModelFinishReason.TOOL_CALLS,
                new ModelUsage(900, 80, 980)
        );
    }

    private static String emptyImpact() {
        return "{\"effects\":[]}";
    }
}
