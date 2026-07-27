package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonNotFoundException;
import com.laishengkai.digitalperson.dialogue.PersonDialogueException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.concurrent.CompletionException;

/** OpenAI-shaped errors for the OpenClaw compatibility endpoint. */
@RestControllerAdvice(assignableTypes = OpenAiChatCompletionsController.class)
public final class OpenAiCompatibilityExceptionHandler {

    @ExceptionHandler(InvalidInternalTokenException.class)
    public ResponseEntity<ErrorEnvelope> unauthorized() {
        return response(
                HttpStatus.UNAUTHORIZED,
                "Invalid API key",
                "invalid_request_error",
                "invalid_api_key"
        );
    }

    @ExceptionHandler(PersonNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> personNotFound(PersonNotFoundException error) {
        return response(
                HttpStatus.NOT_FOUND,
                error.getMessage(),
                "invalid_request_error",
                "person_not_found"
        );
    }

    @ExceptionHandler(PersonDialogueException.class)
    public ResponseEntity<ErrorEnvelope> dialogueFailure() {
        return response(
                HttpStatus.BAD_GATEWAY,
                "The configured language model could not generate a dialogue reply",
                "server_error",
                "dialogue_generation_failed"
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorEnvelope> invalidRequest(IllegalArgumentException error) {
        return response(
                HttpStatus.BAD_REQUEST,
                error.getMessage(),
                "invalid_request_error",
                "invalid_request"
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorEnvelope> unreadableBody() {
        return response(
                HttpStatus.BAD_REQUEST,
                "Request body is missing or malformed",
                "invalid_request_error",
                "invalid_request"
        );
    }

    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<ErrorEnvelope> asyncFailure(CompletionException error) {
        Throwable cause = unwrap(error);
        if (cause instanceof InvalidInternalTokenException) {
            return unauthorized();
        }
        if (cause instanceof PersonNotFoundException notFound) {
            return personNotFound(notFound);
        }
        if (cause instanceof PersonDialogueException) {
            return dialogueFailure();
        }
        if (cause instanceof IllegalArgumentException invalid) {
            return invalidRequest(invalid);
        }
        return internalFailure();
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorEnvelope> internalFailure() {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Chat completion failed",
                "server_error",
                "internal_error"
        );
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ResponseEntity<ErrorEnvelope> response(
            HttpStatus status,
            String message,
            String type,
            String code
    ) {
        String safeMessage = message == null || message.isBlank()
                ? status.getReasonPhrase()
                : message;
        return ResponseEntity.status(status).body(new ErrorEnvelope(
                new ErrorBody(safeMessage, type, code)
        ));
    }

    public record ErrorEnvelope(ErrorBody error) {
    }

    public record ErrorBody(String message, String type, String code) {
    }
}
