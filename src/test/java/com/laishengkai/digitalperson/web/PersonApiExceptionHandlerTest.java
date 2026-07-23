package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.dialogue.LanguageModelException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PersonApiExceptionHandlerTest {

    private final PersonApiExceptionHandler handler = new PersonApiExceptionHandler();

    @Test
    void mapsDirectLanguageModelFailureToStableBadGatewayResponse() {
        ResponseEntity<PersonController.ErrorResponse> response =
                handler.stateEvaluationFailure(new LanguageModelException(
                        "provider details that must not be exposed"
                ));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        PersonController.ErrorResponse body = response.getBody();
        assertEquals("STATE_EVALUATION_FAILED", body.status());
        assertEquals(
                "The configured language model could not evaluate the event",
                body.message()
        );
    }

    @Test
    void unwrapsNestedAsyncLanguageModelFailure() {
        CompletionException failure = new CompletionException(
                new CompletionException(new LanguageModelException("empty assistant"))
        );

        ResponseEntity<PersonController.ErrorResponse> response =
                handler.asyncFailure(failure);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        PersonController.ErrorResponse body = response.getBody();
        assertEquals("STATE_EVALUATION_FAILED", body.status());
    }

    @Test
    void doesNotDisguiseNullDereferenceAsInvalidClientRequest() {
        ResponseEntity<PersonController.ErrorResponse> response =
                handler.internalFailure(new NullPointerException("programming defect"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INTERNAL_ERROR", response.getBody().status());
        assertEquals("Person command failed", response.getBody().message());
    }

    @Test
    void doesNotDisguiseInternalStateFailureAsConflict() {
        ResponseEntity<PersonController.ErrorResponse> response =
                handler.internalFailure(new IllegalStateException("broken invariant"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INTERNAL_ERROR", response.getBody().status());
    }

    @Test
    void nestedUnexpectedRuntimeFailureReturnsStableInternalError() {
        CompletionException failure = new CompletionException(
                new CompletionException(new NullPointerException("hidden detail"))
        );

        ResponseEntity<PersonController.ErrorResponse> response =
                handler.asyncFailure(failure);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INTERNAL_ERROR", response.getBody().status());
        assertEquals("Person command failed", response.getBody().message());
    }
}
