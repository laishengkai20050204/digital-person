package com.laishengkai.digitalperson.dialogue;

import java.util.Objects;

/** End-user text supplied to a model invocation. */
public record UserModelMessage(String text) implements ModelMessage {
    public UserModelMessage {
        text = requireText(text, "text");
    }

    @Override
    public String toString() {
        return "UserModelMessage[textLength=" + text.length() + "]";
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
