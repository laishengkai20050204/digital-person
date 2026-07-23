package com.laishengkai.digitalperson.infrastructure.state;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Retries one semantically invalid state-effect submission with protocol feedback. */
final class StateEffectProtocolGateway implements LanguageModelGateway {
    private final LanguageModelGateway delegate;

    StateEffectProtocolGateway(LanguageModelGateway delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    }

    @Override
    public CompletionStage<LanguageModelResponse> invoke(LanguageModelRequest request) {
        return invokeValidated(
                Objects.requireNonNull(request, "request cannot be null"),
                true
        );
    }

    private CompletionStage<LanguageModelResponse> invokeValidated(
            LanguageModelRequest request,
            boolean retryAllowed
    ) {
        CompletionStage<LanguageModelResponse> invocation = Objects.requireNonNull(
                delegate.invoke(request),
                "delegate stage cannot be null"
        );
        return invocation.thenCompose(response -> {
            try {
                StateEffectProtocol.parseResponse(response);
                return CompletableFuture.completedFuture(response);
            } catch (StateTransitionEvaluationException invalidSubmission) {
                if (!retryAllowed) {
                    return CompletableFuture.failedFuture(invalidSubmission);
                }
                return invokeValidated(
                        StateEffectProtocol.correctionRequest(
                                request,
                                invalidSubmission
                        ),
                        false
                );
            }
        });
    }
}
