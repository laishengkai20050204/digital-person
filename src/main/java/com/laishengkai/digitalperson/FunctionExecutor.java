package com.laishengkai.digitalperson;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Executes functions requested by the dialogue model.
 */
@FunctionalInterface
public interface FunctionExecutor {

    CompletionStage<FunctionResult> execute(DialogueModelResult.FunctionCall functionCall);

    /**
     * Result returned to the dialogue model after a function finishes.
     *
     * @param callId function-call identifier
     * @param name executed function name
     * @param successful whether execution succeeded
     * @param resultJson structured JSON result or error payload
     */
    record FunctionResult(
            String callId,
            String name,
            boolean successful,
            String resultJson
    ) {

        public FunctionResult {
            callId = requireText(callId, "callId");
            name = requireText(name, "name");
            resultJson = resultJson == null || resultJson.isBlank()
                    ? "{}"
                    : resultJson.strip();
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
}
