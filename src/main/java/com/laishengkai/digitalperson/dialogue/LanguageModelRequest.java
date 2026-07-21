package com.laishengkai.digitalperson.dialogue;

import java.util.Objects;

/**
 * Provider-neutral input for one language-model invocation.
 *
 * <p>The system message may be empty. The user message must contain text. The
 * custom {@link #toString()} intentionally exposes lengths only so prompts are
 * not leaked through diagnostics.</p>
 */
public record LanguageModelRequest(
        String systemMessage,
        String userMessage
) {
    public LanguageModelRequest {
        systemMessage = normalize(systemMessage);
        userMessage = requireText(userMessage, "userMessage");
    }

    public static LanguageModelRequest userMessage(String userMessage) {
        return new LanguageModelRequest("", userMessage);
    }

    @Override
    public String toString() {
        return "LanguageModelRequest[systemMessageLength="
                + systemMessage.length()
                + ", userMessageLength="
                + userMessage.length()
                + "]";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
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
