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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Default application-owned implementation of the bounded model/tool loop. */
public final class DefaultAgentExecutor implements AgentExecutor {
    public static final Duration DEFAULT_MODEL_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(3);
    public static final int DEFAULT_MAX_TOOL_RESULT_CHARACTERS = 16_000;
    public static final int MAX_TOOL_ARGUMENT_CHARACTERS = 32_000;
    public static final int MAX_TOOL_CALLS_PER_INVOCATION = 16;
    public static final int MAX_TOTAL_TOOL_EXECUTIONS = 64;

    private static final Logger LOGGER = LoggerFactory.getLogger(
            DefaultAgentExecutor.class
    );

    private final LanguageModelGateway languageModelGateway;
    private final Duration modelTimeout;
    private final Duration toolTimeout;
    private final Duration executionTimeout;
    private final int maxToolResultCharacters;

    public DefaultAgentExecutor(LanguageModelGateway languageModelGateway) {
        this(
                languageModelGateway,
                DEFAULT_MODEL_TIMEOUT,
                DEFAULT_TOOL_TIMEOUT,
                DEFAULT_EXECUTION_TIMEOUT,
                DEFAULT_MAX_TOOL_RESULT_CHARACTERS
        );
    }

    public DefaultAgentExecutor(
            LanguageModelGateway languageModelGateway,
            Duration modelTimeout,
            Duration toolTimeout,
            Duration executionTimeout,
            int maxToolResultCharacters
    ) {
        this.languageModelGateway = Objects.requireNonNull(
                languageModelGateway,
                "languageModelGateway cannot be null"
        );
        this.modelTimeout = requirePositive(modelTimeout, "modelTimeout");
        this.toolTimeout = requirePositive(toolTimeout, "toolTimeout");
        this.executionTimeout = requirePositive(executionTimeout, "executionTimeout");
        if (maxToolResultCharacters <= 0) {
            throw new IllegalArgumentException(
                    "maxToolResultCharacters must be positive"
            );
        }
        this.maxToolResultCharacters = maxToolResultCharacters;
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
        long deadlineNanos = deadlineAfter(startedAtNanos, executionTimeout);

        LOGGER.debug(
                "Starting agent execution: executionId={}, initialMessageCount={}, toolCount={}, maxModelInvocations={}, executionTimeoutMs={}",
                executionId,
                safeRequest.messages().size(),
                toolsByName.size(),
                safeRequest.maxModelInvocations(),
                executionTimeout.toMillis()
        );

        CompletionStage<AgentResult> result = invokeModel(
                executionId,
                safeRequest,
                toolsByName,
                new ArrayList<>(safeRequest.messages()),
                0,
                0,
                ModelUsage.unknown(),
                Set.of(),
                deadlineNanos
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
                        "Agent execution failed: executionId={}, elapsedMs={}, errorType={}",
                        executionId,
                        elapsedMillis,
                        unwrap(error).getClass().getSimpleName()
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
            ModelUsage accumulatedUsage,
            Set<String> completedToolCallIds,
            long deadlineNanos
    ) {
        if (completedModelInvocations >= request.maxModelInvocations()) {
            return failedStage(new AgentExecutionException(
                    "agent exceeded maxModelInvocations="
                            + request.maxModelInvocations()
            ));
        }

        Duration remaining;
        try {
            remaining = remaining(deadlineNanos, "agent execution timed out");
        } catch (AgentExecutionException timeout) {
            return failedStage(timeout);
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

        Duration timeout = min(modelTimeout, remaining);
        return withTimeout(
                responseStage,
                timeout,
                () -> new AgentExecutionException("language model invocation timed out")
        ).thenCompose(response -> handleModelResponse(
                executionId,
                request,
                toolsByName,
                messages,
                invocationNumber,
                completedToolExecutions,
                accumulatedUsage,
                completedToolCallIds,
                deadlineNanos,
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
            Set<String> completedToolCallIds,
            long deadlineNanos,
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

        if (toolCalls.size() > MAX_TOOL_CALLS_PER_INVOCATION) {
            return failedStage(new AgentExecutionException(
                    "model requested too many tools in one invocation"
            ));
        }
        if (completedToolExecutions + toolCalls.size() > MAX_TOTAL_TOOL_EXECUTIONS) {
            return failedStage(new AgentExecutionException(
                    "agent exceeded max total tool executions="
                            + MAX_TOTAL_TOOL_EXECUTIONS
            ));
        }

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

        Set<String> nextCompletedToolCallIds = new HashSet<>(completedToolCallIds);
        AgentExecutionException validationError = validateToolCalls(
                toolCalls,
                toolsByName,
                nextCompletedToolCallIds
        );
        if (validationError != null) {
            return failedStage(validationError);
        }

        return executeTools(
                executionId,
                toolCalls,
                toolsByName,
                deadlineNanos
        ).thenCompose(results -> {
            messages.addAll(results);
            return invokeModel(
                    executionId,
                    request,
                    toolsByName,
                    messages,
                    completedModelInvocations,
                    completedToolExecutions + toolCalls.size(),
                    totalUsage,
                    Set.copyOf(nextCompletedToolCallIds),
                    deadlineNanos
            );
        });
    }

    private CompletionStage<List<ToolResultModelMessage>> executeTools(
            String executionId,
            List<ModelToolCall> toolCalls,
            Map<String, AgentTool> toolsByName,
            long deadlineNanos
    ) {
        boolean parallelSafe = toolCalls.stream()
                .map(call -> toolsByName.get(call.name()))
                .allMatch(tool -> Objects.requireNonNull(
                        tool.executionPolicy(),
                        "tool executionPolicy cannot be null"
                ) == AgentToolExecutionPolicy.PARALLEL_SAFE);
        if (!parallelSafe) {
            CompletionStage<List<ToolResultModelMessage>> sequence =
                    CompletableFuture.completedFuture(List.of());
            for (ModelToolCall toolCall : toolCalls) {
                sequence = sequence.thenCompose(previous -> executeTool(
                        executionId,
                        toolsByName.get(toolCall.name()),
                        toolCall,
                        deadlineNanos
                ).thenApply(result -> {
                    List<ToolResultModelMessage> next = new ArrayList<>(previous);
                    next.add(result);
                    return List.copyOf(next);
                }));
            }
            return sequence;
        }

        List<CompletableFuture<ToolResultModelMessage>> resultFutures =
                new ArrayList<>(toolCalls.size());
        for (ModelToolCall toolCall : toolCalls) {
            resultFutures.add(executeTool(
                    executionId,
                    toolsByName.get(toolCall.name()),
                    toolCall,
                    deadlineNanos
            ).toCompletableFuture());
        }
        return CompletableFuture.allOf(
                resultFutures.toArray(CompletableFuture[]::new)
        ).thenApply(ignored -> resultFutures.stream()
                .map(CompletableFuture::join)
                .toList());
    }

    private CompletionStage<ToolResultModelMessage> executeTool(
            String executionId,
            AgentTool tool,
            ModelToolCall toolCall,
            long deadlineNanos
    ) {
        LOGGER.debug(
                "Executing agent tool: executionId={}, toolCallIdPresent={}, toolName={}, argumentsLength={}, executionPolicy={}",
                executionId,
                !toolCall.id().isEmpty(),
                toolCall.name(),
                toolCall.argumentsJson().length(),
                tool.executionPolicy()
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

        Duration remaining;
        try {
            remaining = remaining(deadlineNanos, "agent execution timed out");
        } catch (AgentExecutionException timeout) {
            return failedStage(timeout);
        }
        Duration timeout = min(toolTimeout, remaining);
        return withTimeout(
                resultStage,
                timeout,
                () -> new AgentExecutionException(
                        "tool execution timed out: " + toolCall.name()
                )
        ).handle((result, error) -> {
            if (error != null) {
                throw toolFailure(toolCall.name(), unwrap(error));
            }
            if (result == null) {
                throw new AgentExecutionException(
                        "tool returned a null result: " + toolCall.name()
                );
            }
            if (result.length() > maxToolResultCharacters) {
                throw new AgentExecutionException(
                        "tool result exceeded maxToolResultCharacters="
                                + maxToolResultCharacters
                                + ": "
                                + toolCall.name()
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
            Map<String, AgentTool> toolsByName,
            Set<String> completedToolCallIds
    ) {
        Set<String> idsInResponse = new HashSet<>();
        for (ModelToolCall toolCall : toolCalls) {
            if (toolCall.id().isEmpty()) {
                return new AgentExecutionException(
                        "model tool call is missing an id: " + toolCall.name()
                );
            }
            if (!idsInResponse.add(toolCall.id())) {
                return new AgentExecutionException(
                        "model returned duplicate tool call id"
                );
            }
            if (completedToolCallIds.contains(toolCall.id())) {
                return new AgentExecutionException(
                        "model reused a tool call id from an earlier invocation"
                );
            }
            if (!toolsByName.containsKey(toolCall.name())) {
                return new AgentExecutionException(
                        "model requested an unavailable tool: " + toolCall.name()
                );
            }
            if (toolCall.argumentsJson().length() > MAX_TOOL_ARGUMENT_CHARACTERS) {
                return new AgentExecutionException(
                        "tool arguments exceeded max characters: " + toolCall.name()
                );
            }
        }
        completedToolCallIds.addAll(idsInResponse);
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

    private static <T> CompletionStage<T> withTimeout(
            CompletionStage<T> stage,
            Duration timeout,
            Supplier<? extends RuntimeException> timeoutException
    ) {
        CompletionStage<T> safeStage = Objects.requireNonNull(stage, "stage cannot be null");
        Duration safeTimeout = requirePositive(timeout, "timeout");
        CompletableFuture<T> source = safeStage.toCompletableFuture();
        CompletableFuture<T> guarded = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(
                safeTimeout.toNanos(),
                TimeUnit.NANOSECONDS
        ).execute(() -> {
            RuntimeException failure = Objects.requireNonNull(
                    timeoutException.get(),
                    "timeoutException cannot return null"
            );
            if (guarded.completeExceptionally(failure)) {
                source.cancel(true);
            }
        });
        source.whenComplete((value, error) -> {
            if (error == null) {
                guarded.complete(value);
            } else {
                guarded.completeExceptionally(unwrap(error));
            }
        });
        guarded.whenComplete((ignored, error) -> {
            if (guarded.isCancelled()) {
                source.cancel(true);
            }
        });
        return guarded;
    }

    private static long deadlineAfter(long startedAtNanos, Duration timeout) {
        try {
            return Math.addExact(startedAtNanos, timeout.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static Duration remaining(long deadlineNanos, String message) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new AgentExecutionException(message);
        }
        return Duration.ofNanos(remainingNanos);
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static Duration requirePositive(Duration value, String fieldName) {
        Duration duration = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        );
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        duration.toNanos();
        return duration;
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
        if (error instanceof AgentExecutionException agentError
                && agentError.getMessage() != null
                && agentError.getMessage().startsWith("tool execution timed out:")) {
            return agentError;
        }
        return new AgentExecutionException("tool execution failed: " + toolName);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = Objects.requireNonNull(error, "error cannot be null");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletionStage<T> failedStage(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
