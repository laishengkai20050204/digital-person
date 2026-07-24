package com.laishengkai.digitalperson.infrastructure.state;

import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelInvocationOptions;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateEffectProtocolGatewayTest {

    @Test
    void usesTypeSpecificSchemaAndRetriesOneInvalidSubmission() {
        SequenceGateway delegate = new SequenceGateway(
                toolResponse(invalidPhysicalEnergy()),
                toolResponse(validPhysicalFatigue())
        );
        StateEffectProtocolGateway gateway = new StateEffectProtocolGateway(delegate);

        LanguageModelResponse response = gateway.invoke(request())
                .toCompletableFuture()
                .join();

        assertEquals(2, delegate.requests().size());
        assertEquals(toolResponse(validPhysicalFatigue()), response);

        String schema = delegate.requests().getFirst()
                .tools()
                .getFirst()
                .parametersJsonSchema();
        assertTrue(schema.contains("\"oneOf\""));
        assertTrue(schema.contains("\"enum\":[\"PHYSICAL\"]"));
        assertTrue(schema.contains("\"enum\":[\"FATIGUE\",\"SLEEPINESS\",\"HUNGER\"]"));

        SystemModelMessage retrySystem = assertInstanceOf(
                SystemModelMessage.class,
                delegate.requests().get(1).messages().getFirst()
        );
        assertTrue(retrySystem.text().contains(
                "effect type PHYSICAL does not support ENERGY"
        ));
        assertTrue(retrySystem.text().contains("跨类型影响必须拆成多个 effect"));
    }

    @Test
    void failsAfterExactlyOneSemanticRetry() {
        SequenceGateway delegate = new SequenceGateway(
                toolResponse(invalidPhysicalEnergy()),
                toolResponse(invalidPhysicalEnergy())
        );
        StateEffectProtocolGateway gateway = new StateEffectProtocolGateway(delegate);

        CompletionException error = assertThrows(
                CompletionException.class,
                () -> gateway.invoke(request()).toCompletableFuture().join()
        );

        StateTransitionEvaluationException cause = assertInstanceOf(
                StateTransitionEvaluationException.class,
                error.getCause()
        );
        assertTrue(cause.getMessage().contains("PHYSICAL does not support ENERGY"));
        assertEquals(2, delegate.requests().size());
    }

    private static LanguageModelRequest request() {
        return new LanguageModelRequest(
                List.of(
                        new SystemModelMessage("Evaluate one state effect."),
                        new UserModelMessage("{}")
                ),
                ModelInvocationOptions.defaults(),
                List.of(StateEffectProtocol.submissionTool())
        );
    }

    private static LanguageModelResponse toolResponse(String argumentsJson) {
        return new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(new ModelToolCall(
                        "call-1",
                        LanguageModelStateTransitionEvaluator.TOOL_NAME,
                        argumentsJson
                ))),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unknown()
        );
    }

    private static String invalidPhysicalEnergy() {
        return """
                {"effects":[{
                  "type":"PHYSICAL",
                  "cause":"运动后身体消耗",
                  "transitions":[{"dimension":"ENERGY","direction":"DECREASE","intensity":"HIGH"}],
                  "endPolicy":"FIXED_TIME",
                  "durationMinutes":60
                }]}
                """;
    }

    private static String validPhysicalFatigue() {
        return """
                {"effects":[{
                  "type":"PHYSICAL",
                  "cause":"运动后身体疲劳",
                  "transitions":[{"dimension":"FATIGUE","direction":"INCREASE","intensity":"HIGH"}],
                  "endPolicy":"FIXED_TIME",
                  "durationMinutes":60
                }]}
                """;
    }

    private static final class SequenceGateway implements LanguageModelGateway {
        private final ArrayDeque<LanguageModelResponse> responses;
        private final List<LanguageModelRequest> requests = new ArrayList<>();

        private SequenceGateway(LanguageModelResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public CompletionStage<LanguageModelResponse> invoke(LanguageModelRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(responses.removeFirst());
        }

        private List<LanguageModelRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
