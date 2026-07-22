package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonCreationConflictException;
import com.laishengkai.digitalperson.application.PersonNotFoundException;
import com.laishengkai.digitalperson.application.PersonVersionConflictException;
import com.laishengkai.digitalperson.application.UnsettledPersonEventException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.concurrent.CompletionException;

/** Stable error contract for the protected person API. */
@RestControllerAdvice(assignableTypes = {
        PersonController.class,
        PersonEventController.class
})
public final class PersonApiExceptionHandler {

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

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<PersonController.ErrorResponse> stateConflict(
            IllegalStateException error
    ) {
        return response(HttpStatus.CONFLICT, "EVENT_STATE_CONFLICT", error.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
    public ResponseEntity<PersonController.ErrorResponse> invalidRequest(RuntimeException error) {
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
        Throwable cause = error.getCause();
        if (cause instanceof PersonNotFoundException notFound) {
            return notFound(notFound);
        }
        if (cause instanceof PersonVersionConflictException conflict) {
            return versionConflict(conflict);
        }
        if (cause instanceof UnsettledPersonEventException unsettled) {
            return unsettledEvent(unsettled);
        }
        if (cause instanceof IllegalStateException stateConflict) {
            return stateConflict(stateConflict);
        }
        if (cause instanceof RuntimeException invalid) {
            return invalidRequest(invalid);
        }
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Person command failed"
        );
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
