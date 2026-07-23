package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.InvalidPersonActivityDecisionException;
import com.laishengkai.digitalperson.application.PersonCreationConflictException;
import com.laishengkai.digitalperson.application.PersonNotFoundException;
import com.laishengkai.digitalperson.application.PersonVersionConflictException;
import com.laishengkai.digitalperson.application.UnsettledPersonEventException;
import com.laishengkai.digitalperson.dialogue.LanguageModelException;
import com.laishengkai.digitalperson.infrastructure.activity.PersonActivityDecisionException;
import com.laishengkai.digitalperson.infrastructure.state.StateTransitionEvaluationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Set;
import java.util.concurrent.CompletionException;

/** Stable error contract for the protected person API. */
@RestControllerAdvice(assignableTypes = {
        PersonController.class,
        PersonEventController.class,
        PersonActivityDecisionController.class
})
public final class PersonApiExceptionHandler {
    private static final Set<String> REQUEST_NULL_VALIDATION_FRAMES = Set.of(
            PersonController.CreatePersonRequest.class.getName() + "#toPersonality",
            PersonController.IdentityRequest.class.getName() + "#requiredText",
            PersonController.PersonalityRequest.class.getName() + "#required",
            PersonEventController.class.getName() + "#requireText"
    );

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

    /** Explicit caller-input conversion and identifier parsing failures. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PersonController.ErrorResponse> invalidRequest(
            IllegalArgumentException error
    ) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", error.getMessage());
    }

    /**
     * A small number of legacy request DTO validators still use requireNonNull. Only those exact
     * conversion frames remain client errors; every other null dereference stays a server error.
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<PersonController.ErrorResponse> nullPointerFailure(
            NullPointerException error
    ) {
        if (isLegacyRequestNullValidation(error)) {
            return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", error.getMessage());
        }
        return internalFailure(error);
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
        if (cause instanceof PersonNotFoundException notFound) {
            return notFound(notFound);
        }
        if (cause instanceof PersonVersionConflictException conflict) {
            return versionConflict(conflict);
        }
        if (cause instanceof UnsettledPersonEventException unsettled) {
            return unsettledEvent(unsettled);
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
        if (cause instanceof IllegalArgumentException invalid) {
            return invalidRequest(invalid);
        }
        if (cause instanceof NullPointerException nullPointer) {
            return nullPointerFailure(nullPointer);
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

    private static boolean isLegacyRequestNullValidation(NullPointerException error) {
        for (StackTraceElement frame : error.getStackTrace()) {
            String key = frame.getClassName() + "#" + frame.getMethodName();
            if (REQUEST_NULL_VALIDATION_FRAMES.contains(key)) {
                return true;
            }
        }
        return false;
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
