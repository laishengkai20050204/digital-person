package com.laishengkai.digitalperson.dialogue;

import java.util.Objects;

/** Developer instructions supplied to a model invocation. */
public record SystemModelMessage(String text) implements ModelMessage {
    public SystemModelMessage {
        text = requireText(text, "text");
    }

    @Override
    public String toString() {
        return "SystemModelMessage[textLength=" + text.length() + "]";
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
