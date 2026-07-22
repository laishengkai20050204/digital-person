package com.laishengkai.digitalperson.dialogue;

import java.util.Objects;

/** One tool execution request emitted by an assistant message. */
public record ModelToolCall(
        String id,
        String name,
        String argumentsJson
) {
    public ModelToolCall {
        id = normalize(id);
        name = requireText(name, "name");
        argumentsJson = requireText(argumentsJson, "argumentsJson");
    }

    @Override
    public String toString() {
        return "ModelToolCall[idPresent="
                + !id.isEmpty()
                + ", name="
                + name
                + ", argumentsLength="
                + argumentsJson.length()
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
