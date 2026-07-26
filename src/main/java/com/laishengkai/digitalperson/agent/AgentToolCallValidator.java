package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.ModelToolCall;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates model-issued calls before any application tool is executed. */
final class AgentToolCallValidator {
    private final int maxArgumentCharacters;

    AgentToolCallValidator(int maxArgumentCharacters) {
        if (maxArgumentCharacters <= 0) {
            throw new IllegalArgumentException(
                    "maxArgumentCharacters must be positive"
            );
        }
        this.maxArgumentCharacters = maxArgumentCharacters;
    }

    Set<String> validate(
            List<ModelToolCall> toolCalls,
            AgentToolRegistry registry,
            Set<String> completedToolCallIds
    ) {
        Set<String> idsInResponse = new HashSet<>();
        for (ModelToolCall toolCall : toolCalls) {
            if (toolCall.id().isEmpty()) {
                throw new AgentExecutionException(
                        "model tool call is missing an id: " + toolCall.name()
                );
            }
            if (!idsInResponse.add(toolCall.id())) {
                throw new AgentExecutionException(
                        "model returned duplicate tool call id"
                );
            }
            if (completedToolCallIds.contains(toolCall.id())) {
                throw new AgentExecutionException(
                        "model reused a tool call id from an earlier invocation"
                );
            }
            if (!registry.contains(toolCall.name())) {
                throw new AgentExecutionException(
                        "model requested an unavailable tool: " + toolCall.name()
                );
            }
            if (toolCall.argumentsJson().length() > maxArgumentCharacters) {
                throw new AgentExecutionException(
                        "tool arguments exceeded max characters: " + toolCall.name()
                );
            }
        }
        Set<String> completed = new HashSet<>(completedToolCallIds);
        completed.addAll(idsInResponse);
        return Set.copyOf(completed);
    }
}
