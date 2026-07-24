package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.InvalidPersonActivityDecisionException;
import com.laishengkai.digitalperson.application.PersonCreationConflictException;
import com.laishengkai.digitalperson.application.PersonNotFoundException;
import com.laishengkai.digitalperson.application.PersonVersionConflictException;
import com.laishengkai.digitalperson.application.UnsettledPersonEventException;
import com.laishengkai.digitalperson.dialogue.LanguageModelException;
import com.laishengkai.digitalperson.dialogue.PersonDialogueException;
import com.laishengkai.digitalperson.infrastructure.activity.PersonActivityDecisionException;
import com.laishengkai.digitalperson.infrastructure.state.StateTransitionEvaluationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.concurrent.CompletionException;

/** Stable error contract for protected internal and person APIs. */
@RestControllerAdvice(assignableTypes = {
        PersonController.class,
        PersonEventController.class,
        PersonActivityDecisionController.class,
        PersonDialogueController.class,
        MemoryTestController.class
})
public final class PersonApiExceptionHandler {

    @ExceptionHandler(InvalidInternalTokenException.class)
    public ResponseEntity<PersonController.ErrorResponse> unauthorized() {
        return response(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Invalid internal token"
        );
    }

    @ExceptionHandler(PersonNotFoundException.class)
    public ResponseEntity<PersonController.ErrorResponse> notFound(
            PersonNotFoundException error
    ) {
        return response(HttpStatus.NOT_FOUND, "PERSON_NOT_FOUND", error.getMessage());
    }

    @ExceptionHandler(PersonCreationConflictException.class)
    public ResponseEntity<PersonController.ErrorResponse> creationConflict(
            PersonCreationConflictException error
    ) {
        return response(HttpStatus.CONFLICT, "PERSON_ALREADY_EXISTS", error.getMessage());
    }

    @ExceptionHandler(PersonVersionConflictException.class)
    public ResponseEntity<PersonController.ErrorResponse> versionConflict(
            PersonVersionConflictException error
    ) {
        return response(HttpStatus.CONFLICT, "PERSON_VERSION_CONFLICT", error.getMessage());
    }

    @ExceptionHandler(UnsettledPersonEventException.class)
    public ResponseEntity<PersonController.ErrorResponse> unsettledEvent(
            UnsettledPersonEventException error
    ) {
        return response(HttpStatus.CONFLICT, "PERSON_EVENT_STATE_UNSETTLED", error.getMessage());
    }

    @ExceptionHandler(PersonDialogueException.class)
    public ResponseEntity<PersonController.ErrorResponse> dialogueFailure() {
        return response(
                HttpStatus.BAD_GATEWAY,
                "DIALOGUE_GENERATION_FAILED",
                "The configured language model could not generate a dialogue reply"
        );
    }

    @ExceptionHandler({
            LanguageModelException.class,
            StateTransitionEvaluationException.class
    })
    public ResponseEntity<PersonController.ErrorResponse> stateEvaluationFailure(
            RuntimeException ignored
    ) {
        return response(
                HttpStatus.BAD_GATEWAY,
                "STATE_EVALUATION_FAILED",
                "The configured language model could not evaluate the event"
        );
    }

    @ExceptionHandler({
            PersonActivityDecisionException.class,
            InvalidPersonActivityDecisionException.class
    })
    public ResponseEntity<PersonController.ErrorResponse> activityDecisionFailure(
            RuntimeException ignored
    ) {
        return response(
                HttpStatus.BAD_GATEWAY,
                "ACTIVITY_DECISION_FAILED",
                "The configured language model returned no executable activity plan"
        );
    }

    @ExceptionHandler(MemoryTestUnavailableException.class)
    public ResponseEntity<PersonController.ErrorResponse> memoryTestUnavailable(
            MemoryTestUnavailableException error
    ) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MEMORY_TEST_UNAVAILABLE",
                error.getMessage()
        );
    }

    @ExceptionHandler(MemoryTestProviderException.class)
    public ResponseEntity<PersonController.ErrorResponse> memoryProviderFailure() {
        return response(
                HttpStatus.BAD_GATEWAY,
                "MEMORY_PROVIDER_FAILED",
                "The configured memory provider could not complete the request"
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PersonController.ErrorResponse> invalidRequest(
            IllegalArgumentException error
    ) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", error.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<PersonController.ErrorResponse> unreadableBody() {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Request body is missing or malformed"
        );
    }

    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<PersonController.ErrorResponse> asyncFailure(
            CompletionException error
    ) {
        Throwable cause = unwrap(error);
        if (cause instanceof InvalidInternalTokenException) {
            return unauthorized();
        }
        if (cause instanceof PersonNotFoundException notFound) {
            return notFound(notFound);
        }
        if (cause instanceof PersonVersionConflictException conflict) {
            return versionConflict(conflict);
        }
        if (cause instanceof UnsettledPersonEventException unsettled) {
            return unsettledEvent(unsettled);
        }
        if (cause instanceof PersonDialogueException) {
            return dialogueFailure();
        }
        if (cause instanceof LanguageModelException modelFailure) {
            return stateEvaluationFailure(modelFailure);
        }
        if (cause instanceof StateTransitionEvaluationException invalidEffects) {
            return stateEvaluationFailure(invalidEffects);
        }
        if (cause instanceof PersonActivityDecisionException decisionFailure) {
            return activityDecisionFailure(decisionFailure);
        }
        if (cause instanceof InvalidPersonActivityDecisionException invalidDecision) {
            return activityDecisionFailure(invalidDecision);
        }
        if (cause instanceof MemoryTestUnavailableException unavailable) {
            return memoryTestUnavailable(unavailable);
        }
        if (cause instanceof MemoryTestProviderException) {
            return memoryProviderFailure();
        }
        if (cause instanceof IllegalArgumentException invalid) {
            return invalidRequest(invalid);
        }
        return internalFailure(cause);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<PersonController.ErrorResponse> internalFailure(
            Throwable ignored
    ) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Person command failed"
        );
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ResponseEntity<PersonController.ErrorResponse> response(
            HttpStatus status,
            String code,
            String message
    ) {
        String safeMessage = message == null || message.isBlank()
                ? status.getReasonPhrase()
                : message;
        return ResponseEntity.status(status)
                .body(new PersonController.ErrorResponse(code, safeMessage));
    }
}
