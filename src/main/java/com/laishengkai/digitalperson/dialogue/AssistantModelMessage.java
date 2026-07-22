package com.laishengkai.digitalperson.dialogue;

import java.util.List;
import java.util.Objects;

/**
 * One assistant-role message.
 *
 * <p>An assistant message may contain text, tool requests, or both. Tool
 * requests are not a separate role; they are structured content emitted by the
 * assistant.</p>
 */
public record AssistantModelMessage(
        String text,
        List<ModelToolCall> toolCalls
) implements ModelMessage {
    public AssistantModelMessage {
        text = text == null ? "" : text.strip();
        toolCalls = List.copyOf(Objects.requireNonNull(
                toolCalls,
                "toolCalls cannot be null"
        ));
        if (toolCalls.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("toolCalls cannot contain null");
        }
        if (text.isEmpty() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "assistant message must contain text or tool calls"
            );
        }
    }

    public static AssistantModelMessage text(String text) {
        return new AssistantModelMessage(text, List.of());
    }

    public static AssistantModelMessage toolCalls(List<ModelToolCall> toolCalls) {
        return new AssistantModelMessage("", toolCalls);
    }

    @Override
    public String toString() {
        return "AssistantModelMessage[textLength="
                + text.length()
                + ", toolCallCount="
                + toolCalls.size()
                + "]";
    }
}
