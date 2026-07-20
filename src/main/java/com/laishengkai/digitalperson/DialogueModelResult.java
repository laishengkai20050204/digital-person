package com.laishengkai.digitalperson;

import java.util.List;
import java.util.Objects;

/**
 * Internal result produced by a dialogue model.
 *
 * <p>A model turn either requests one or more function calls, produces final reply segments,
 * or deliberately produces no reply. Function calls are consumed by {@link Person} and are not
 * exposed to message adapters.
 */
public record DialogueModelResult(
        String decisionSummary,
        List<DialogueResult.ReplySegment> replySegments,
        List<FunctionCall> functionCalls,
        Long reconsiderAfterMillis
) {

    public DialogueModelResult {
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

        if (!functionCalls.isEmpty()) {
            if (!replySegments.isEmpty()) {
                throw new IllegalArgumentException(
                        "A function-call turn cannot contain final reply segments"
                );
            }
            if (reconsiderAfterMillis != null) {
                throw new IllegalArgumentException(
                        "A function-call turn cannot contain reconsiderAfterMillis"
                );
            }
        } else if (!replySegments.isEmpty() && reconsiderAfterMillis != null) {
            throw new IllegalArgumentException(
                    "A final reply cannot contain reconsiderAfterMillis"
            );
        }
    }

    public boolean requiresFunctionCalls() {
        return !functionCalls.isEmpty();
    }

    public DialogueResult toFinalResult() {
        if (requiresFunctionCalls()) {
            throw new IllegalStateException(
                    "Function calls must be executed before creating the final dialogue result"
            );
        }

        DialogueResult.Outcome outcome = replySegments.isEmpty()
                ? DialogueResult.Outcome.NO_REPLY
                : DialogueResult.Outcome.REPLY;

        return new DialogueResult(
                outcome,
                decisionSummary,
                replySegments,
                reconsiderAfterMillis
        );
    }

    /**
     * One function requested by the model.
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
