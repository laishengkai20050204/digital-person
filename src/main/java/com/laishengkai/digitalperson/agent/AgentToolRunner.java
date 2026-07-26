package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.async.DeadlineGuard;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ToolResultModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Executes validated tool calls while preserving serial and parallel-safe semantics. */
final class AgentToolRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentToolRunner.class);

    private final Duration toolTimeout;
    private final int maxToolResultCharacters;

    AgentToolRunner(Duration toolTimeout, int maxToolResultCharacters) {
        this.toolTimeout = AgentExecutionBudget.requirePositive(
                toolTimeout,
                "toolTimeout"
        );
        if (maxToolResultCharacters <= 0) {
            throw new IllegalArgumentException(
                    "maxToolResultCharacters must be positive"
            );
        }
        this.maxToolResultCharacters = maxToolResultCharacters;
    }

    CompletionStage<List<ToolResultModelMessage>> execute(
            String executionId,
            List<ModelToolCall> toolCalls,
            AgentToolRegistry registry,
            AgentExecutionBudget budget
    ) {
        if (!registry.allParallelSafe(toolCalls)) {
            CompletionStage<List<ToolResultModelMessage>> sequence =
                    CompletableFuture.completedFuture(List.of());
            for (ModelToolCall toolCall : toolCalls) {
                sequence = sequence.thenCompose(previous -> executeOne(
                        executionId,
                        registry.tool(toolCall.name()),
                        toolCall,
                        budget
                ).thenApply(result -> {
                    List<ToolResultModelMessage> next = new ArrayList<>(previous);
                    next.add(result);
                    return List.copyOf(next);
                }));
            }
            return sequence;
        }

        List<CompletableFuture<ToolResultModelMessage>> futures =
                new ArrayList<>(toolCalls.size());
        for (ModelToolCall toolCall : toolCalls) {
            futures.add(executeOne(
                    executionId,
                    registry.tool(toolCall.name()),
                    toolCall,
                    budget
            ).toCompletableFuture());
        }
        return CompletableFuture.allOf(
                futures.toArray(CompletableFuture[]::new)
        ).thenApply(ignored -> futures.stream()
                .map(CompletableFuture::join)
                .toList());
    }

    private CompletionStage<ToolResultModelMessage> executeOne(
            String executionId,
            AgentTool tool,
            ModelToolCall toolCall,
            AgentExecutionBudget budget
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
            return CompletableFuture.failedFuture(toolFailure(toolCall.name(), error));
        }
        if (resultStage == null) {
            return CompletableFuture.failedFuture(new AgentExecutionException(
                    "tool returned a null stage: " + toolCall.name()
            ));
        }

        final Duration timeout;
        try {
            timeout = budget.cap(toolTimeout);
        } catch (AgentExecutionException expired) {
            return CompletableFuture.failedFuture(expired);
        }
        return DeadlineGuard.within(
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

    private static AgentExecutionException toolFailure(
            String toolName,
            Throwable error
    ) {
        if (error instanceof AgentExecutionException agentError
                && agentError.getMessage() != null
                && agentError.getMessage().startsWith("tool execution timed out:")) {
            return agentError;
        }
        return new AgentExecutionException(
                "tool execution failed: " + toolName,
                error
        );
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = Objects.requireNonNull(error, "error cannot be null");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
