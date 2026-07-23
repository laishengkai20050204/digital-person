package com.laishengkai.digitalperson.infrastructure.state;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateTransition;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Executes the production state-effect protocol and retains request and response diagnostics. */
public final class StateTransitionEvaluationDiagnostic {

    private static final int MAX_DIAGNOSTIC_MESSAGE_LENGTH = 1_000;

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
                return Result.failure(request, response, invocationError);
            }
            try {
                EventStateImpact impact =
                        LanguageModelStateTransitionEvaluator.parseResponse(response);
                return Result.success(request, response, impact);
            } catch (RuntimeException protocolError) {
                return Result.failure(request, response, protocolError);
            }
        });
    }

    private static Throwable unwrapAsync(Throwable error) {
        Throwable current = Objects.requireNonNull(error, "error cannot be null");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = unwrapAsync(error);
        for (int depth = 0; depth < 32; depth++) {
            Throwable next = current.getCause();
            if (next == null || next == current) {
                break;
            }
            current = next;
        }
        return current;
    }

    private static String safeMessage(Throwable error) {
        String message = normalize(error == null ? null : error.getMessage());
        if (message.isEmpty()) {
            return "";
        }
        String redacted = message
                .replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer <redacted>")
                .replaceAll("\\bsk-[A-Za-z0-9_-]{8,}\\b", "<redacted>");
        if (redacted.length() <= MAX_DIAGNOSTIC_MESSAGE_LENGTH) {
            return redacted;
        }
        return redacted.substring(0, MAX_DIAGNOSTIC_MESSAGE_LENGTH) + "…";
    }

    private static String diagnosticMessage(
            Throwable exposedError,
            Throwable rootCause
    ) {
        String outerMessage = safeMessage(exposedError);
        if (rootCause == exposedError) {
            return outerMessage;
        }
        String rootType = rootCause.getClass().getSimpleName();
        String rootMessage = safeMessage(rootCause);
        String rootDetails = rootMessage.isEmpty()
                ? rootType
                : rootType + ": " + rootMessage;
        return outerMessage.isEmpty()
                ? "rootCause=" + rootDetails
                : outerMessage + " | rootCause=" + rootDetails;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    public record Result(
            LanguageModelRequest request,
            LanguageModelResponse response,
            EventStateImpact impact,
            String errorType,
            String errorMessage,
            String rootCauseType,
            String rootCauseMessage
    ) {
        public Result {
            impact = Objects.requireNonNullElse(impact, EventStateImpact.none());
            errorType = normalize(errorType);
            errorMessage = normalize(errorMessage);
            rootCauseType = normalize(rootCauseType);
            rootCauseMessage = normalize(rootCauseMessage);
        }

        public static Result success(
                LanguageModelRequest request,
                LanguageModelResponse response,
                EventStateImpact impact
        ) {
            return new Result(
                    Objects.requireNonNull(request, "request cannot be null"),
                    Objects.requireNonNull(response, "response cannot be null"),
                    Objects.requireNonNull(impact, "impact cannot be null"),
                    "",
                    "",
                    "",
                    ""
            );
        }

        public static Result failure(
                LanguageModelRequest request,
                LanguageModelResponse response,
                Throwable error
        ) {
            Throwable exposedError = unwrapAsync(error);
            Throwable rootCause = rootCause(exposedError);
            return new Result(
                    request,
                    response,
                    EventStateImpact.none(),
                    exposedError.getClass().getSimpleName(),
                    diagnosticMessage(exposedError, rootCause),
                    rootCause.getClass().getSimpleName(),
                    safeMessage(rootCause)
            );
        }

        /** Compatibility view used by direction-based diagnostic expectations. */
        public List<StateTransition> transitions() {
            return impact.effects().stream()
                    .flatMap(effect -> effect.transitions().stream())
                    .toList();
        }

        public boolean successful() {
            return request != null
                    && response != null
                    && errorType.isEmpty()
                    && errorMessage.isEmpty()
                    && rootCauseType.isEmpty()
                    && rootCauseMessage.isEmpty();
        }
    }
}
