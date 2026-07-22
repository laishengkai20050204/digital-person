package com.laishengkai.digitalperson.dialogue;

import java.util.List;
import java.util.Objects;

/** Provider-neutral output from exactly one language-model invocation. */
public record LanguageModelResponse(
        AssistantModelMessage message,
        ModelFinishReason finishReason,
        ModelUsage usage
) {
    public LanguageModelResponse {
        message = Objects.requireNonNull(message, "message cannot be null");
        finishReason = Objects.requireNonNullElse(
                finishReason,
                ModelFinishReason.UNKNOWN
        );
        usage = Objects.requireNonNullElseGet(usage, ModelUsage::unknown);
    }

    public static LanguageModelResponse text(String text) {
        return new LanguageModelResponse(
                AssistantModelMessage.text(text),
                ModelFinishReason.STOP,
                ModelUsage.unknown()
        );
    }

    public String text() {
        return message.text();
    }

    public List<ModelToolCall> toolCalls() {
        return message.toolCalls();
    }

    @Override
    public String toString() {
        return "LanguageModelResponse[textLength="
                + message.text().length()
                + ", toolCallCount="
                + message.toolCalls().size()
                + ", finishReason="
                + finishReason
                + ", usage="
                + usage
                + "]";
    }
}
