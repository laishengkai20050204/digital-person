package com.laishengkai.digitalperson.infrastructure.state;

import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateTransition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageModelStateTransitionEvaluatorTest {

    @Test
    void shouldReadTransitionsFromExactlyOneSubmissionToolCall() {
        RecordingGateway gateway = new RecordingGateway(toolResponse("""
                {
                  "transitions": [
                    {"dimension": "VALENCE", "shape": 0.35},
                    {"dimension": "LONELINESS", "shape": -0.25}
                  ]
                }
                """));
        LanguageModelStateTransitionEvaluator evaluator =
                new LanguageModelStateTransitionEvaluator(gateway);

        List<StateTransition> transitions = evaluator.evaluate(
                state(),
                event()
        ).toCompletableFuture().join();

        assertEquals(List.of(
                new StateTransition(StateDimension.VALENCE, 0.35),
                new StateTransition(StateDimension.LONELINESS, -0.25)
        ), transitions);

        LanguageModelRequest request = gateway.request();
        assertEquals(ModelToolChoice.REQUIRED, request.options().toolChoice());
        assertEquals(1, request.tools().size());
        assertEquals(
                LanguageModelStateTransitionEvaluator.TOOL_NAME,
                request.tools().getFirst().name()
        );
        assertEquals(2, request.messages().size());
        UserModelMessage userMessage = assertInstanceOf(
                UserModelMessage.class,
                request.messages().get(1)
        );
        assertTrue(userMessage.text().contains("\"activityType\":\"STUDY\""));
        assertTrue(userMessage.text().contains("\"valence\":0.2"));
    }

    @Test
    void shouldAcceptEmptyTransitionSubmission() {
        LanguageModelStateTransitionEvaluator evaluator =
                new LanguageModelStateTransitionEvaluator(
                        ignored -> CompletableFuture.completedFuture(
                                toolResponse("{\"transitions\":[]}")
                        )
                );

        List<StateTransition> transitions = evaluator.evaluate(
                state(),
                event()
        ).toCompletableFuture().join();

        assertEquals(List.of(), transitions);
    }

    @Test
    void shouldRejectResponseWithoutSubmissionToolCall() {
        LanguageModelStateTransitionEvaluator evaluator = evaluatorReturning(
                new LanguageModelResponse(
                        AssistantModelMessage.text("no change"),
                        ModelFinishReason.STOP,
                        ModelUsage.unknown()
                )
        );

        StateTransitionEvaluationException error = failureOf(evaluator);

        assertTrue(error.getMessage().contains("exactly once"));
    }

    @Test
    void shouldRejectMultipleToolCalls() {
        LanguageModelStateTransitionEvaluator evaluator = evaluatorReturning(
                new LanguageModelResponse(
                        AssistantModelMessage.toolCalls(List.of(
                                toolCall("call-1", "{\"transitions\":[]}"),
                                toolCall("call-2", "{\"transitions\":[]}")
                        )),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unknown()
                )
        );

        StateTransitionEvaluationException error = failureOf(evaluator);

        assertTrue(error.getMessage().contains("received 2"));
    }

    @Test
    void shouldRejectUnexpectedToolName() {
        LanguageModelStateTransitionEvaluator evaluator = evaluatorReturning(
                new LanguageModelResponse(
                        AssistantModelMessage.toolCalls(List.of(
                                new ModelToolCall(
                                        "call-1",
                                        "another_tool",
                                        "{\"transitions\":[]}"
                                )
                        )),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unknown()
                )
        );

        StateTransitionEvaluationException error = failureOf(evaluator);

        assertTrue(error.getMessage().contains("unexpected"));
    }

    @Test
    void shouldRejectMalformedOrStructurallyInvalidArguments() {
        LanguageModelStateTransitionEvaluator malformed = evaluatorReturning(
                toolResponse("not-json")
        );
        LanguageModelStateTransitionEvaluator unknownField = evaluatorReturning(
                toolResponse("""
                        {
                          "transitions": [],
                          "explanation": "not allowed"
                        }
                        """)
        );

        assertTrue(failureOf(malformed).getMessage().contains("invalid"));
        assertTrue(failureOf(unknownField).getMessage().contains("invalid"));
    }

    @Test
    void shouldRejectDuplicateDimensions() {
        LanguageModelStateTransitionEvaluator evaluator = evaluatorReturning(
                toolResponse("""
                        {
                          "transitions": [
                            {"dimension":"ENERGY","shape":0.2},
                            {"dimension":"ENERGY","shape":-0.1}
                          ]
                        }
                        """)
        );

        StateTransitionEvaluationException error = failureOf(evaluator);

        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void shouldRejectMissingZeroOrUnknownTransitionFields() {
        LanguageModelStateTransitionEvaluator missingShape = evaluatorReturning(
                toolResponse("""
                        {"transitions":[{"dimension":"ENERGY"}]}
                        """)
        );
        LanguageModelStateTransitionEvaluator zeroShape = evaluatorReturning(
                toolResponse("""
                        {"transitions":[{"dimension":"ENERGY","shape":0.0}]}
                        """)
        );
        LanguageModelStateTransitionEvaluator unknownDimension = evaluatorReturning(
                toolResponse("""
                        {"transitions":[{"dimension":"MOOD","shape":0.2}]}
                        """)
        );

        assertTrue(failureOf(missingShape).getMessage().contains("shape is required"));
        assertTrue(failureOf(zeroShape).getMessage().contains("invalid shape"));
        assertTrue(failureOf(unknownDimension).getMessage().contains("unknown"));
    }

    private static StateTransitionEvaluationException failureOf(
            LanguageModelStateTransitionEvaluator evaluator
    ) {
        CompletionException error = assertThrows(
                CompletionException.class,
                () -> evaluator.evaluate(state(), event()).toCompletableFuture().join()
        );
        return assertInstanceOf(
                StateTransitionEvaluationException.class,
                error.getCause()
        );
    }

    private static LanguageModelStateTransitionEvaluator evaluatorReturning(
            LanguageModelResponse response
    ) {
        return new LanguageModelStateTransitionEvaluator(
                ignored -> CompletableFuture.completedFuture(response)
        );
    }

    private static LanguageModelResponse toolResponse(String argumentsJson) {
        return new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(
                        toolCall("call-1", argumentsJson)
                )),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unknown()
        );
    }

    private static ModelToolCall toolCall(String id, String argumentsJson) {
        return new ModelToolCall(
                id,
                LanguageModelStateTransitionEvaluator.TOOL_NAME,
                argumentsJson
        );
    }

    private static PersonStateSnapshot state() {
        return new PersonStateSnapshot(
                0.2,
                0.7,
                0.3,
                0.6,
                0.4,
                0.8,
                0.2,
                0.1,
                0.3,
                0.4,
                0.5
        );
    }

    private static PersonEvent event() {
        return new PersonEvent(
                ActivityType.STUDY,
                "Prepare for the exam",
                "library",
                TimeRange.openEnded(Instant.parse("2026-07-22T02:00:00Z"))
        );
    }

    private static final class RecordingGateway implements LanguageModelGateway {
        private final LanguageModelResponse response;
        private final AtomicReference<LanguageModelRequest> request =
                new AtomicReference<>();

        private RecordingGateway(LanguageModelResponse response) {
            this.response = response;
        }

        @Override
        public CompletionStage<LanguageModelResponse> invoke(
                LanguageModelRequest modelRequest
        ) {
            request.set(modelRequest);
            return CompletableFuture.completedFuture(response);
        }

        private LanguageModelRequest request() {
            return request.get();
        }
    }
}
