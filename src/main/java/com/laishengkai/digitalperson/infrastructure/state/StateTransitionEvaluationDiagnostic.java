package com.laishengkai.digitalperson.infrastructure.state;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateTransition;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Executes the exact production state-evaluation protocol while retaining the
 * full provider-neutral request and raw response for internal diagnostics.
 */
public final class StateTransitionEvaluationDiagnostic {

    private final LanguageModelGateway languageModelGateway;

    public StateTransitionEvaluationDiagnostic(
            LanguageModelGateway languageModelGateway
    ) {
        this.languageModelGateway = Objects.requireNonNull(
                languageModelGateway,
                "languageModelGateway cannot be null"
        );
    }

    public CompletionStage<Result> evaluate(StateEvaluationContext context) {
        final LanguageModelRequest request;
        try {
            request = LanguageModelStateTransitionEvaluator.createRequest(context);
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(Result.failure(null, null, error));
        }

        final CompletionStage<LanguageModelResponse> responseStage;
        try {
            responseStage = Objects.requireNonNull(
                    languageModelGateway.invoke(request),
                    "languageModelGateway stage cannot be null"
            );
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(Result.failure(request, null, error));
        }

        return responseStage.handle((response, invocationError) -> {
            if (invocationError != null) {
                return Result.failure(request, response, unwrap(invocationError));
            }

            try {
                List<StateTransition> transitions =
                        LanguageModelStateTransitionEvaluator.parseResponse(response);
                return Result.success(request, response, transitions);
            } catch (RuntimeException protocolError) {
                return Result.failure(request, response, protocolError);
            }
        });
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public record Result(
            LanguageModelRequest request,
            LanguageModelResponse response,
            List<StateTransition> transitions,
            String errorType,
            String errorMessage
    ) {
        public Result {
            transitions = List.copyOf(Objects.requireNonNullElse(transitions, List.of()));
            errorType = normalize(errorType);
            errorMessage = normalize(errorMessage);
        }

        public static Result success(
                LanguageModelRequest request,
                LanguageModelResponse response,
                List<StateTransition> transitions
        ) {
            return new Result(
                    Objects.requireNonNull(request, "request cannot be null"),
                    Objects.requireNonNull(response, "response cannot be null"),
                    transitions,
                    "",
                    ""
            );
        }

        public static Result failure(
                LanguageModelRequest request,
                LanguageModelResponse response,
                Throwable error
        ) {
            Throwable safeError = Objects.requireNonNull(error, "error cannot be null");
            return new Result(
                    request,
                    response,
                    List.of(),
                    safeError.getClass().getSimpleName(),
                    safeError.getMessage()
            );
        }

        public boolean successful() {
            return request != null
                    && response != null
                    && errorType.isEmpty()
                    && errorMessage.isEmpty();
        }

        private static String normalize(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
