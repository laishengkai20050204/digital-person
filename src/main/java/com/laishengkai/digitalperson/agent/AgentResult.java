package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelMessage;
import com.laishengkai.digitalperson.dialogue.ModelUsage;

import java.util.List;
import java.util.Objects;

/** Final output and immutable trace from one successful agent execution. */
public record AgentResult(
        LanguageModelResponse finalResponse,
        List<ModelMessage> messages,
        int modelInvocationCount,
        int toolExecutionCount,
        ModelUsage totalUsage
) {
    public AgentResult {
        finalResponse = Objects.requireNonNull(
                finalResponse,
                "finalResponse cannot be null"
        );
        messages = List.copyOf(Objects.requireNonNull(
                messages,
                "messages cannot be null"
        ));
        if (modelInvocationCount <= 0) {
            throw new IllegalArgumentException(
                    "modelInvocationCount must be positive"
            );
        }
        if (toolExecutionCount < 0) {
            throw new IllegalArgumentException(
                    "toolExecutionCount cannot be negative"
            );
        }
        totalUsage = Objects.requireNonNullElseGet(
                totalUsage,
                ModelUsage::unknown
        );
    }

    public String text() {
        return finalResponse.text();
    }

    @Override
    public String toString() {
        return "AgentResult[messageCount="
                + messages.size()
                + ", modelInvocationCount="
                + modelInvocationCount
                + ", toolExecutionCount="
                + toolExecutionCount
                + ", finalResponse="
                + finalResponse
                + ", totalUsage="
                + totalUsage
                + "]";
    }
}
