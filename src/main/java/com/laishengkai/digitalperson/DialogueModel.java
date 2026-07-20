package com.laishengkai.digitalperson;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Produces one internal model turn without blocking the caller.
 */
@FunctionalInterface
public interface DialogueModel {

    CompletionStage<DialogueModelResult> generateReply(
            Person person,
            DialogueContext context
    );

    /**
     * Context for the current user turn, including functions already executed during this turn.
     */
    record DialogueContext(
            String userMessage,
            List<FunctionExchange> functionExchanges
    ) {

        public DialogueContext {
            userMessage = Objects.requireNonNull(
                    userMessage,
                    "userMessage cannot be null"
            ).strip();
            if (userMessage.isEmpty()) {
                throw new IllegalArgumentException("userMessage cannot be blank");
            }
            functionExchanges = List.copyOf(Objects.requireNonNull(
                    functionExchanges,
                    "functionExchanges cannot be null"
            ));
        }
    }

    /**
     * A completed function call and its result, retained so the model adapter can reconstruct
     * the tool-call conversation accurately.
     */
    record FunctionExchange(
            DialogueModelResult.FunctionCall functionCall,
            FunctionExecutor.FunctionResult functionResult
    ) {

        public FunctionExchange {
            functionCall = Objects.requireNonNull(
                    functionCall,
                    "functionCall cannot be null"
            );
            functionResult = Objects.requireNonNull(
                    functionResult,
                    "functionResult cannot be null"
            );

            if (!functionCall.callId().equals(functionResult.callId())) {
                throw new IllegalArgumentException(
                        "Function result callId does not match its function call"
                );
            }
            if (!functionCall.name().equals(functionResult.name())) {
                throw new IllegalArgumentException(
                        "Function result name does not match its function call"
                );
            }
        }
    }
}
