package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelInvocationOptions;
import com.laishengkai.digitalperson.dialogue.ModelMessage;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.dialogue.ToolResultModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DefaultAgentExecutorTest {

    @Test
    void shouldReturnFinalResponseWithoutExecutingTools() {
        RecordingGateway gateway = new RecordingGateway(List.of(
                response(
                        AssistantModelMessage.text("done"),
                        ModelFinishReason.STOP,
                        new ModelUsage(3, 2, 5)
                )
        ));
        DefaultAgentExecutor executor = new DefaultAgentExecutor(gateway);

        AgentResult result = executor.execute(new AgentRequest(
                List.of(new UserModelMessage("hello")),
                ModelInvocationOptions.defaults(),
                List.of(),
                3
        )).toCompletableFuture().join();

        assertEquals("done", result.text());
        assertEquals(1, result.modelInvocationCount());
        assertEquals(0, result.toolExecutionCount());
        assertEquals(new ModelUsage(3, 2, 5), result.totalUsage());
        assertEquals(2, result.messages().size());
        assertEquals(1, gateway.requests().size());
    }

    @Test
    void shouldExecuteToolAppendResultAndContinueModelLoop() {
        RecordingGateway gateway = new RecordingGateway(List.of(
                response(
                        AssistantModelMessage.toolCalls(List.of(
                                new ModelToolCall(
                                        "call-1",
                                        "get_weather",
                                        "{\"city\":\"Shanghai\"}"
                                )
                        )),
                        ModelFinishReason.TOOL_CALLS,
                        new ModelUsage(4, 1, 5)
                ),
                response(
                        AssistantModelMessage.text("sunny"),
                        ModelFinishReason.STOP,
                        new ModelUsage(8, 2, 10)
                )
        ));
        AtomicReference<String> receivedArguments = new AtomicReference<>();
        AgentTool weather = tool(
                "get_weather",
                arguments -> {
                    receivedArguments.set(arguments);
                    return CompletableFuture.completedFuture(
                            "{\"condition\":\"sunny\"}"
                    );
                }
        );
        DefaultAgentExecutor executor = new DefaultAgentExecutor(gateway);

        AgentResult result = executor.execute(new AgentRequest(
                List.of(new UserModelMessage("weather?")),
                ModelInvocationOptions.defaults(),
                List.of(weather),
                4
        )).toCompletableFuture().join();

        assertEquals("sunny", result.text());
        assertEquals(2, result.modelInvocationCount());
        assertEquals(1, result.toolExecutionCount());
        assertEquals(new ModelUsage(12, 3, 15), result.totalUsage());
        assertEquals("{\"city\":\"Shanghai\"}", receivedArguments.get());
        assertEquals(2, gateway.requests().size());

        LanguageModelRequest secondRequest = gateway.requests().get(1);
        assertEquals(3, secondRequest.messages().size());
        assertInstanceOf(
                AssistantModelMessage.class,
                secondRequest.messages().get(1)
        );
        ToolResultModelMessage toolResult = assertInstanceOf(
                ToolResultModelMessage.class,
                secondRequest.messages().get(2)
        );
        assertEquals("call-1", toolResult.toolCallId());
        assertEquals("get_weather", toolResult.toolName());
        assertEquals(4, result.messages().size());
    }

    @Test
    void shouldPreserveModelToolCallOrderWhenToolsComplete() {
        RecordingGateway gateway = new RecordingGateway(List.of(
                response(
                        AssistantModelMessage.toolCalls(List.of(
                                new ModelToolCall("call-a", "first", "{}"),
                                new ModelToolCall("call-b", "second", "{}")
                        )),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unknown()
                ),
                LanguageModelResponse.text("complete")
        ));
        AgentTool first = tool(
                "first",
                ignored -> CompletableFuture.completedFuture("A")
        );
        AgentTool second = tool(
                "second",
                ignored -> CompletableFuture.completedFuture("B")
        );

        AgentResult result = new DefaultAgentExecutor(gateway).execute(
                new AgentRequest(
                        List.of(new UserModelMessage("run both")),
                        ModelInvocationOptions.defaults(),
                        List.of(first, second),
                        4
                )
        ).toCompletableFuture().join();

        ToolResultModelMessage firstResult = assertInstanceOf(
                ToolResultModelMessage.class,
                result.messages().get(2)
        );
        ToolResultModelMessage secondResult = assertInstanceOf(
                ToolResultModelMessage.class,
                result.messages().get(3)
        );
        assertEquals("call-a", firstResult.toolCallId());
        assertEquals("call-b", secondResult.toolCallId());
        assertEquals(2, result.toolExecutionCount());
    }

    @Test
    void shouldFailWhenModelRequestsUnavailableTool() {
        RecordingGateway gateway = new RecordingGateway(List.of(
                response(
                        AssistantModelMessage.toolCalls(List.of(
                                new ModelToolCall("call-1", "missing", "{}")
                        )),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unknown()
                )
        ));

        Throwable error = failureOf(new DefaultAgentExecutor(gateway).execute(
                new AgentRequest(
                        List.of(new UserModelMessage("run")),
                        ModelInvocationOptions.defaults(),
                        List.of(tool(
                                "available",
                                ignored -> CompletableFuture.completedFuture("ok")
                        )),
                        3
                )
        ));

        AgentExecutionException agentError = assertInstanceOf(
                AgentExecutionException.class,
                error
        );
        assertTrue(agentError.getMessage().contains("unavailable tool"));
    }

    @Test
    void shouldNotExecuteToolsWhenNoInvocationRemainsForFinalResponse() {
        RecordingGateway gateway = new RecordingGateway(List.of(
                response(
                        AssistantModelMessage.toolCalls(List.of(
                                new ModelToolCall("call-1", "lookup", "{}")
                        )),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unknown()
                )
        ));
        AtomicBoolean executed = new AtomicBoolean(false);
        AgentTool tool = tool("lookup", ignored -> {
            executed.set(true);
            return CompletableFuture.completedFuture("ok");
        });

        Throwable error = failureOf(new DefaultAgentExecutor(gateway).execute(
                new AgentRequest(
                        List.of(new UserModelMessage("run")),
                        ModelInvocationOptions.defaults(),
                        List.of(tool),
                        1
                )
        ));

        AgentExecutionException agentError = assertInstanceOf(
                AgentExecutionException.class,
                error
        );
        assertTrue(agentError.getMessage().contains("final permitted"));
        assertFalse(executed.get());
    }

    @Test
    void shouldPropagateSanitizedToolFailure() {
        RecordingGateway gateway = new RecordingGateway(List.of(
                response(
                        AssistantModelMessage.toolCalls(List.of(
                                new ModelToolCall("call-1", "lookup", "{}")
                        )),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unknown()
                )
        ));
        AgentTool failingTool = tool(
                "lookup",
                ignored -> CompletableFuture.failedFuture(
                        new IllegalStateException("secret provider detail")
                )
        );

        Throwable error = failureOf(new DefaultAgentExecutor(gateway).execute(
                new AgentRequest(
                        List.of(new UserModelMessage("run")),
                        ModelInvocationOptions.defaults(),
                        List.of(failingTool),
                        3
                )
        ));

        AgentExecutionException agentError = assertInstanceOf(
                AgentExecutionException.class,
                error
        );
        assertEquals("tool execution failed: lookup", agentError.getMessage());
    }

    private static LanguageModelResponse response(
            AssistantModelMessage message,
            ModelFinishReason finishReason,
            ModelUsage usage
    ) {
        return new LanguageModelResponse(message, finishReason, usage);
    }

    private static AgentTool tool(
            String name,
            ToolFunction function
    ) {
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
            public CompletionStage<String> execute(String argumentsJson) {
                return function.execute(argumentsJson);
            }
        };
    }

    private static Throwable failureOf(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().join();
            fail("expected agent execution to fail");
            return null;
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            while (cause instanceof CompletionException
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return cause;
        }
    }

    @FunctionalInterface
    private interface ToolFunction {
        CompletionStage<String> execute(String argumentsJson);
    }

    private static final class RecordingGateway implements LanguageModelGateway {
        private final Deque<LanguageModelResponse> responses;
        private final List<LanguageModelRequest> requests = new ArrayList<>();

        private RecordingGateway(List<LanguageModelResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public CompletionStage<LanguageModelResponse> invoke(
                LanguageModelRequest request
        ) {
            requests.add(request);
            if (responses.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new AssertionError("unexpected model invocation")
                );
            }
            return CompletableFuture.completedFuture(responses.removeFirst());
        }

        private List<LanguageModelRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
