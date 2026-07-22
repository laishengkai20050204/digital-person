package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonCreationConflictException;
import com.laishengkai.digitalperson.application.PersonNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable error contract for the protected person API. */
@RestControllerAdvice(assignableTypes = PersonController.class)
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
