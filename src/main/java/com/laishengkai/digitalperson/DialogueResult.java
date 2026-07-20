package com.laishengkai.digitalperson;

import java.util.List;
import java.util.Objects;

/**
 * Structured result returned by the dialogue model.
 *
 * <p>The result separates a deliberate no-reply decision from transport or model failures.
 * Failures should complete the asynchronous operation exceptionally rather than being encoded
 * as {@link Action#NO_REPLY}.
 */
public record DialogueResult(
        Action action,
        String decisionSummary,
        List<ReplySegment> replySegments,
        List<FunctionCall> functionCalls,
        Long reconsiderAfterMillis
) {

    public DialogueResult {
        action = Objects.requireNonNull(action, "action cannot be null");
        decisionSummary = decisionSummary == null ? "" : decisionSummary.strip();
        replySegments = List.copyOf(Objects.requireNonNull(
                replySegments,
                "replySegments cannot be null"
        ));
        functionCalls = List.copyOf(Objects.requireNonNull(
                functionCalls,
                "functionCalls cannot be null"
        ));

        if (reconsiderAfterMillis != null && reconsiderAfterMillis < 0) {
            throw new IllegalArgumentException("reconsiderAfterMillis cannot be negative");
        }

        validateAction(action, replySegments, functionCalls);
    }

    private static void validateAction(
            Action action,
            List<ReplySegment> replySegments,
            List<FunctionCall> functionCalls
    ) {
        switch (action) {
            case NO_REPLY -> {
                requireEmpty(replySegments, "NO_REPLY cannot contain reply segments");
                requireEmpty(functionCalls, "NO_REPLY cannot contain function calls");
            }
            case REPLY -> {
                requireNotEmpty(replySegments, "REPLY requires at least one reply segment");
                requireEmpty(functionCalls, "REPLY cannot contain function calls");
            }
            case FUNCTION_CALL -> {
                requireEmpty(replySegments, "FUNCTION_CALL cannot contain reply segments");
                requireNotEmpty(functionCalls, "FUNCTION_CALL requires at least one function call");
            }
            case REPLY_AND_FUNCTION_CALL -> {
                requireNotEmpty(replySegments, "REPLY_AND_FUNCTION_CALL requires reply segments");
                requireNotEmpty(functionCalls, "REPLY_AND_FUNCTION_CALL requires function calls");
            }
        }
    }

    private static void requireEmpty(List<?> values, String message) {
        if (!values.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireNotEmpty(List<?> values, String message) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    public enum Action {
        NO_REPLY,
        REPLY,
        FUNCTION_CALL,
        REPLY_AND_FUNCTION_CALL
    }

    /**
     * One independently sent message bubble.
     *
     * @param content text to send
     * @param delayBeforeMillis delay before sending this segment
     */
    public record ReplySegment(String content, long delayBeforeMillis) {

        public ReplySegment {
            content = Objects.requireNonNull(content, "content cannot be null").strip();
            if (content.isEmpty()) {
                throw new IllegalArgumentException("content cannot be blank");
            }
            if (delayBeforeMillis < 0) {
                throw new IllegalArgumentException("delayBeforeMillis cannot be negative");
            }
        }
    }

    /**
     * A future action requested by the model.
     *
     * @param callId identifier used to match the function result
     * @param name registered function name
     * @param argumentsJson JSON object containing the function arguments
     */
    public record FunctionCall(String callId, String name, String argumentsJson) {

        public FunctionCall {
            callId = requireText(callId, "callId");
            name = requireText(name, "name");
            argumentsJson = argumentsJson == null || argumentsJson.isBlank()
                    ? "{}"
                    : argumentsJson.strip();
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
