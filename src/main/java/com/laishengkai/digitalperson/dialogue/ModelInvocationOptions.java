package com.laishengkai.digitalperson.dialogue;

import java.util.List;
import java.util.Objects;

/** Common request-level options supported across chat-model providers. */
public record ModelInvocationOptions(
        Double temperature,
        Integer maxOutputTokens,
        List<String> stopSequences,
        ModelToolChoice toolChoice,
        ModelResponseFormat responseFormat
) {
    public ModelInvocationOptions {
        if (temperature != null
                && (!Double.isFinite(temperature) || temperature < 0.0)) {
            throw new IllegalArgumentException(
                    "temperature must be finite and non-negative"
            );
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }

        stopSequences = List.copyOf(Objects.requireNonNullElse(
                stopSequences,
                List.of()
        ));
        if (stopSequences.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("stopSequences cannot contain null");
        }
        if (stopSequences.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(
                    "stopSequences cannot contain blank values"
            );
        }

        toolChoice = Objects.requireNonNullElse(toolChoice, ModelToolChoice.AUTO);
        responseFormat = Objects.requireNonNullElseGet(
                responseFormat,
                ModelResponseFormat::text
        );
    }

    public static ModelInvocationOptions defaults() {
        return new ModelInvocationOptions(
                null,
                null,
                List.of(),
                ModelToolChoice.AUTO,
                ModelResponseFormat.text()
        );
    }
}
