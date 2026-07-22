package com.laishengkai.digitalperson.dialogue;

import java.util.Objects;

/** Result returned by the application for one previous assistant tool request. */
public record ToolResultModelMessage(
        String toolCallId,
        String toolName,
        String result
) implements ModelMessage {
    public ToolResultModelMessage {
        toolCallId = requireText(toolCallId, "toolCallId");
        toolName = requireText(toolName, "toolName");
        result = Objects.requireNonNull(result, "result cannot be null");
    }

    @Override
    public String toString() {
        return "ToolResultModelMessage[toolCallIdPresent=true, toolName="
                + toolName
                + ", resultLength="
                + result.length()
                + "]";
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }
}
