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
        PersonController.ErrorResponse body = assertNotNull(response.getBody());
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
        PersonController.ErrorResponse body = assertNotNull(response.getBody());
        assertEquals("STATE_EVALUATION_FAILED", body.status());
    }
}
