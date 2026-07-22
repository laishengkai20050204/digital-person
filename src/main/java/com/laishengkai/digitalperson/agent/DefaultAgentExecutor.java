package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelMessage;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.dialogue.ToolResultModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Default application-owned implementation of the bounded model/tool loop. */
public final class DefaultAgentExecutor implements AgentExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            DefaultAgentExecutor.class
    );

    private final LanguageModelGateway languageModelGateway;

    public DefaultAgentExecutor(LanguageModelGateway languageModelGateway) {
        this.languageModelGateway = Objects.requireNonNull(
                languageModelGateway,
                "languageModelGateway cannot be null"
        );
    }

    @Override
    public CompletionStage<AgentResult> execute(AgentRequest request) {
        AgentRequest safeRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        Map<String, AgentTool> toolsByName = indexTools(safeRequest.tools());
        String executionId = UUID.randomUUID().toString();
        long startedAtNanos = System.nanoTime();

        LOGGER.debug(
                "Starting agent execution: executionId={}, initialMessageCount={}, toolCount={}, maxModelInvocations={}",
                executionId,
                safeRequest.messages().size(),
                toolsByName.size(),
                safeRequest.maxModelInvocations()
        );

        CompletionStage<AgentResult> result = invokeModel(
                executionId,
                safeRequest,
                toolsByName,
                new ArrayList<>(safeRequest.messages()),
                0,
                0,
                ModelUsage.unknown()
        );

        return result.whenComplete((agentResult, error) -> {
            long elapsedMillis = elapsedMillis(startedAtNanos);
            if (error == null) {
                LOGGER.debug(
                        "Completed agent execution: executionId={}, modelInvocationCount={}, toolExecutionCount={}, elapsedMs={}",
                        executionId,
                        agentResult.modelInvocationCount(),
                        agentResult.toolExecutionCount(),
                        elapsedMillis
                );
            } else {
                LOGGER.warn(
                        "Agent execution failed: executionId={}, elapsedMs={}",
                        executionId,
                        elapsedMillis,
                        error
                );
            }
        });
    }

    private CompletionStage<AgentResult> invokeModel(
            String executionId,
            AgentRequest request,
            Map<String, AgentTool> toolsByName,
            List<ModelMessage> messages,
            int completedModelInvocations,
            int completedToolExecutions,
            ModelUsage accumulatedUsage
    ) {
        if (completedModelInvocations >= request.maxModelInvocations()) {
            return failedStage(new AgentExecutionException(
                    "agent exceeded maxModelInvocations="
                            + request.maxModelInvocations()
            ));
        }

        int invocationNumber = completedModelInvocations + 1;
        LanguageModelRequest modelRequest = request.toModelRequest(
                List.copyOf(messages)
        );

        LOGGER.debug(
                "Invoking model for agent: executionId={}, invocationNumber={}, messageCount={}, toolCount={}",
                executionId,
                invocationNumber,
                modelRequest.messages().size(),
                modelRequest.tools().size()
        );

        CompletionStage<LanguageModelResponse> responseStage;
        try {
            responseStage = languageModelGateway.invoke(modelRequest);
        } catch (RuntimeException error) {
            return failedStage(new AgentExecutionException(
                    "language model gateway failed before returning a stage",
                    error
            ));
        }
        if (responseStage == null) {
            return failedStage(new AgentExecutionException(
                    "language model gateway returned a null stage"
            ));
        }

        return responseStage.thenCompose(response -> handleModelResponse(
                executionId,
                request,
                toolsByName,
                messages,
                invocationNumber,
                completedToolExecutions,
                accumulatedUsage,
                response
        ));
    }

    private CompletionStage<AgentResult> handleModelResponse(
            String executionId,
            AgentRequest request,
            Map<String, AgentTool> toolsByName,
            List<ModelMessage> previousMessages,
            int completedModelInvocations,
            int completedToolExecutions,
            ModelUsage accumulatedUsage,
            LanguageModelResponse response
    ) {
        if (response == null) {
            return failedStage(new AgentExecutionException(
                    "language model gateway returned a null response"
            ));
        }

        List<ModelMessage> messages = new ArrayList<>(previousMessages);
        AssistantModelMessage assistantMessage = response.message();
        messages.add(assistantMessage);
        ModelUsage totalUsage = addUsage(accumulatedUsage, response.usage());
        List<ModelToolCall> toolCalls = assistantMessage.toolCalls();

        if (toolCalls.isEmpty()) {
            return CompletableFuture.completedFuture(new AgentResult(
                    response,
                    messages,
                    completedModelInvocations,
                    completedToolExecutions,
                    totalUsage
            ));
        }

        if (completedModelInvocations >= request.maxModelInvocations()) {
            return failedStage(new AgentExecutionException(
                    "model requested tools on the final permitted invocation"
            ));
        }

        AgentExecutionException validationError = validateToolCalls(
                toolCalls,
                toolsByName
        );
        if (validationError != null) {
            return failedStage(validationError);
        }

        List<CompletableFuture<ToolResultModelMessage>> resultFutures =
                new ArrayList<>(toolCalls.size());
        for (ModelToolCall toolCall : toolCalls) {
            resultFutures.add(executeTool(
                    executionId,
                    toolsByName.get(toolCall.name()),
                    toolCall
            ).toCompletableFuture());
        }

        CompletableFuture<Void> allTools = CompletableFuture.allOf(
                resultFutures.toArray(CompletableFuture[]::new)
        );
        return allTools.thenCompose(ignored -> {
            for (CompletableFuture<ToolResultModelMessage> resultFuture
                    : resultFutures) {
                messages.add(resultFuture.join());
            }
            return invokeModel(
                    executionId,
                    request,
                    toolsByName,
                    messages,
                    completedModelInvocations,
                    completedToolExecutions + toolCalls.size(),
                    totalUsage
            );
        });
    }

    private CompletionStage<ToolResultModelMessage> executeTool(
            String executionId,
            AgentTool tool,
            ModelToolCall toolCall
    ) {
        LOGGER.debug(
                "Executing agent tool: executionId={}, toolCallIdPresent={}, toolName={}, argumentsLength={}",
                executionId,
                !toolCall.id().isEmpty(),
                toolCall.name(),
                toolCall.argumentsJson().length()
        );

        CompletionStage<String> resultStage;
        try {
            resultStage = tool.execute(toolCall.argumentsJson());
        } catch (RuntimeException error) {
            return failedStage(toolFailure(toolCall.name(), error));
        }
        if (resultStage == null) {
            return failedStage(new AgentExecutionException(
                    "tool returned a null stage: " + toolCall.name()
            ));
        }

        return resultStage.handle((result, error) -> {
            if (error != null) {
                throw toolFailure(toolCall.name(), error);
            }
            if (result == null) {
                throw new AgentExecutionException(
                        "tool returned a null result: " + toolCall.name()
                );
            }
            return new ToolResultModelMessage(
                    toolCall.id(),
                    toolCall.name(),
                    result
            );
        });
    }

    private static AgentExecutionException validateToolCalls(
            List<ModelToolCall> toolCalls,
            Map<String, AgentTool> toolsByName
    ) {
        Set<String> ids = new HashSet<>();
        for (ModelToolCall toolCall : toolCalls) {
            if (toolCall.id().isEmpty()) {
                return new AgentExecutionException(
                        "model tool call is missing an id: " + toolCall.name()
                );
            }
            if (!ids.add(toolCall.id())) {
                return new AgentExecutionException(
                        "model returned duplicate tool call id"
                );
            }
            if (!toolsByName.containsKey(toolCall.name())) {
                return new AgentExecutionException(
                        "model requested an unavailable tool: " + toolCall.name()
                );
            }
        }
        return null;
    }

    private static Map<String, AgentTool> indexTools(List<AgentTool> tools) {
        Map<String, AgentTool> toolsByName = new HashMap<>();
        for (AgentTool tool : tools) {
            String name = tool.specification().name();
            AgentTool previous = toolsByName.put(name, tool);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate agent tool name: " + name
                );
            }
        }
        return Map.copyOf(toolsByName);
    }

    private static ModelUsage addUsage(ModelUsage left, ModelUsage right) {
        return new ModelUsage(
                addNullable(left.inputTokens(), right.inputTokens()),
                addNullable(left.outputTokens(), right.outputTokens()),
                addNullable(left.totalTokens(), right.totalTokens())
        );
    }

    private static Integer addNullable(Integer left, Integer right) {
        if (left == null && right == null) {
            return null;
        }
        return Objects.requireNonNullElse(left, 0)
                + Objects.requireNonNullElse(right, 0);
    }

    private static AgentExecutionException toolFailure(
            String toolName,
            Throwable error
    ) {
        return new AgentExecutionException(
                "tool execution failed: " + toolName,
                error
        );
    }

    private static <T> CompletionStage<T> failedStage(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
