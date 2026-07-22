package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelProperties;
import com.laishengkai.digitalperson.memory.MemoryAvailability;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateTransition;
import com.laishengkai.digitalperson.state.StateTransitionEvaluator;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateEvaluationTestControllerTest {

    @Test
    void shouldRejectIncorrectTokenWithoutCallingEvaluator() {
        StateTransitionEvaluator evaluator = context -> {
            throw new AssertionError("evaluator should not be called");
        };
        StateEvaluationTestController controller = controller(evaluator);

        ResponseEntity<StateEvaluationTestController.StateEvaluationTestResponse> response =
                controller.testEvaluation("wrong-token").toCompletableFuture().join();

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UNAUTHORIZED", response.getBody().status());
        assertEquals(0, response.getBody().transitionCount());
    }

    @Test
    void shouldSendCompleteFixedContextAndReturnUpForExpectedEffect() {
        AtomicReference<StateEvaluationContext> captured = new AtomicReference<>();
        StateTransitionEvaluator evaluator = context -> {
            captured.set(context);
            return CompletableFuture.completedFuture(List.of(
                    new StateTransition(StateDimension.VALENCE, 0.40),
                    new StateTransition(StateDimension.LONELINESS, -0.55)
            ));
        };
        StateEvaluationTestController controller = controller(evaluator);

        ResponseEntity<StateEvaluationTestController.StateEvaluationTestResponse> response =
                controller.testEvaluation("expected-token").toCompletableFuture().join();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().status());
        assertTrue(response.getBody().expectedEffectObserved());
        assertEquals(2, response.getBody().transitionCount());

        StateEvaluationContext context = captured.get();
        assertNotNull(context);
        assertTrue(context.personality().emotionality() > 0.8);
        assertTrue(context.currentState().loneliness() > 0.8);
        assertEquals("USER", context.newEvent().owner().name());
        assertFalse(context.activeEvents().isEmpty());
        assertFalse(context.recentEvents().isEmpty());
        assertEquals(MemoryAvailability.AVAILABLE, context.memory().availability());
        assertEquals(2, context.memory().items().size());
        assertEquals(2, context.recentConversation().size());
        assertNotNull(context.evaluationTime());
    }

    @Test
    void shouldRejectEmptyTransitionResult() {
        StateTransitionEvaluator evaluator = context ->
                CompletableFuture.completedFuture(List.of());
        StateEvaluationTestController controller = controller(evaluator);

        ResponseEntity<StateEvaluationTestController.StateEvaluationTestResponse> response =
                controller.testEvaluation("expected-token").toCompletableFuture().join();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("EMPTY_TRANSITIONS", response.getBody().status());
    }

    @Test
    void shouldRejectTransitionWithUnexpectedDirectionOnly() {
        StateTransitionEvaluator evaluator = context ->
                CompletableFuture.completedFuture(List.of(
                        new StateTransition(StateDimension.HUNGER, 0.20)
                ));
        StateEvaluationTestController controller = controller(evaluator);

        ResponseEntity<StateEvaluationTestController.StateEvaluationTestResponse> response =
                controller.testEvaluation("expected-token").toCompletableFuture().join();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UNEXPECTED_EFFECT", response.getBody().status());
        assertFalse(response.getBody().expectedEffectObserved());
    }

    @Test
    void shouldReturnBadGatewayWhenEvaluatorFails() {
        StateTransitionEvaluator evaluator = context ->
                CompletableFuture.failedFuture(new IllegalStateException("provider unavailable"));
        StateEvaluationTestController controller = controller(evaluator);

        ResponseEntity<StateEvaluationTestController.StateEvaluationTestResponse> response =
                controller.testEvaluation("expected-token").toCompletableFuture().join();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().status());
    }

    private static StateEvaluationTestController controller(
            StateTransitionEvaluator evaluator
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
        return new StateEvaluationTestController(evaluator, properties);
    }
}
