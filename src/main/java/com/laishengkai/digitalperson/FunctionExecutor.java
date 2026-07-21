package com.laishengkai.digitalperson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface FunctionExecutor {

    CompletionStage<FunctionResult> execute(DialogueModelResult.FunctionCall functionCall);

    default CompletionStage<List<DialogueModel.FunctionExchange>> executeAll(
            List<DialogueModelResult.FunctionCall> functionCalls
    ) {
        CompletionStage<List<DialogueModel.FunctionExchange>> stage =
                CompletableFuture.completedFuture(List.of());

        for (DialogueModelResult.FunctionCall functionCall : functionCalls) {
            stage = stage.thenCompose(exchanges ->
                    execute(functionCall).thenApply(functionResult -> {
                        List<DialogueModel.FunctionExchange> updated =
                                new ArrayList<>(exchanges);
                        updated.add(new DialogueModel.FunctionExchange(
                                functionCall,
                                functionResult
                        ));
                        return List.copyOf(updated);
                    })
            );
        }

        return stage;
    }

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
