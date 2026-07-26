package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.async.DeadlineGuard;
import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelMessage;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

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
    private final Duration executionTimeout;
    private final AgentToolCallValidator toolCallValidator;
    private final AgentToolRunner toolRunner;

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
        this.modelTimeout = AgentExecutionBudget.requirePositive(
                modelTimeout,
                "modelTimeout"
        );
        this.executionTimeout = AgentExecutionBudget.requirePositive(
                executionTimeout,
                "executionTimeout"
        );
        this.toolCallValidator = new AgentToolCallValidator(
                MAX_TOOL_ARGUMENT_CHARACTERS
        );
        this.toolRunner = new AgentToolRunner(
                toolTimeout,
                maxToolResultCharacters
        );
    }

    @Override
    public CompletionStage<AgentResult> execute(AgentRequest request) {
        AgentRequest safeRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        AgentToolRegistry registry = AgentToolRegistry.from(safeRequest.tools());
        String executionId = UUID.randomUUID().toString();
        long startedAtNanos = System.nanoTime();
        AgentExecutionBudget budget = AgentExecutionBudget.start(executionTimeout);

        LOGGER.debug(
                "Starting agent execution: executionId={}, initialMessageCount={}, toolCount={}, maxModelInvocations={}, executionTimeoutMs={}",
                executionId,
                safeRequest.messages().size(),
                registry.size(),
                safeRequest.maxModelInvocations(),
                executionTimeout.toMillis()
        );

        CompletionStage<AgentResult> result = invokeModel(
                executionId,
                safeRequest,
                registry,
                new ArrayList<>(safeRequest.messages()),
                0,
                0,
                ModelUsage.unknown(),
                Set.of(),
                budget
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
            AgentToolRegistry registry,
            List<ModelMessage> messages,
            int completedModelInvocations,
            int completedToolExecutions,
            ModelUsage accumulatedUsage,
            Set<String> completedToolCallIds,
            AgentExecutionBudget budget
    ) {
        if (completedModelInvocations >= request.maxModelInvocations()) {
            return CompletableFuture.failedFuture(new AgentExecutionException(
                    "agent exceeded maxModelInvocations="
                            + request.maxModelInvocations()
            ));
        }

        final Duration timeout;
        try {
            timeout = budget.cap(modelTimeout);
        } catch (AgentExecutionException expired) {
            return CompletableFuture.failedFuture(expired);
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
            return CompletableFuture.failedFuture(new AgentExecutionException(
                    "language model gateway failed before returning a stage",
                    error
            ));
        }
        if (responseStage == null) {
            return CompletableFuture.failedFuture(new AgentExecutionException(
                    "language model gateway returned a null stage"
            ));
        }

        return DeadlineGuard.within(
                responseStage,
                timeout,
                () -> new AgentExecutionException("language model invocation timed out")
        ).thenCompose(response -> handleModelResponse(
                executionId,
                request,
                registry,
                messages,
                invocationNumber,
                completedToolExecutions,
                accumulatedUsage,
                completedToolCallIds,
                budget,
                response
        ));
    }

    private CompletionStage<AgentResult> handleModelResponse(
            String executionId,
            AgentRequest request,
            AgentToolRegistry registry,
            List<ModelMessage> previousMessages,
            int completedModelInvocations,
            int completedToolExecutions,
            ModelUsage accumulatedUsage,
            Set<String> completedToolCallIds,
            AgentExecutionBudget budget,
            LanguageModelResponse response
    ) {
        if (response == null) {
            return CompletableFuture.failedFuture(new AgentExecutionException(
                    "language model gateway returned a null response"
            ));
        }

        List<ModelMessage> messages = new ArrayList<>(previousMessages);
        AssistantModelMessage assistantMessage = response.message();
        messages.add(assistantMessage);
        ModelUsage totalUsage = AgentUsageAccumulator.add(
                accumulatedUsage,
                response.usage()
        );
        List<ModelToolCall> toolCalls = assistantMessage.toolCalls();

        if (toolCalls.size() > MAX_TOOL_CALLS_PER_INVOCATION) {
            return CompletableFuture.failedFuture(new AgentExecutionException(
                    "model requested too many tools in one invocation"
            ));
        }
        if (completedToolExecutions + toolCalls.size() > MAX_TOTAL_TOOL_EXECUTIONS) {
            return CompletableFuture.failedFuture(new AgentExecutionException(
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
            return CompletableFuture.failedFuture(new AgentExecutionException(
                    "model requested tools on the final permitted invocation"
            ));
        }

        final Set<String> nextCompletedToolCallIds;
        try {
            nextCompletedToolCallIds = toolCallValidator.validate(
                    toolCalls,
                    registry,
                    completedToolCallIds
            );
        } catch (AgentExecutionException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }

        return toolRunner.execute(
                executionId,
                toolCalls,
                registry,
                budget
        ).thenCompose(results -> {
            messages.addAll(results);
            return invokeModel(
                    executionId,
                    request,
                    registry,
                    messages,
                    completedModelInvocations,
                    completedToolExecutions + toolCalls.size(),
                    totalUsage,
                    nextCompletedToolCallIds,
                    budget
            );
        });
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = Objects.requireNonNull(error, "error cannot be null");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
