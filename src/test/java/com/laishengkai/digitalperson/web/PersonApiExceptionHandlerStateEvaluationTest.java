package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.infrastructure.state.StateTransitionEvaluationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Objects;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersonApiExceptionHandlerStateEvaluationTest {

    private final PersonApiExceptionHandler handler = new PersonApiExceptionHandler();

    @Test
    void mapsDirectInvalidStateEffectOutputToBadGateway() {
        ResponseEntity<PersonController.ErrorResponse> response =
                handler.stateEvaluationFailure(
                        new StateTransitionEvaluationException(
                                "effect type PHYSICAL does not support ENERGY"
                        )
                );

        assertStateEvaluationFailure(response);
    }

    @Test
    void mapsWrappedInvalidStateEffectOutputToBadGateway() {
        ResponseEntity<PersonController.ErrorResponse> response = handler.asyncFailure(
                new CompletionException(new StateTransitionEvaluationException(
                        "effect type PHYSICAL does not support ENERGY"
                ))
        );

        assertStateEvaluationFailure(response);
    }

    private static void assertStateEvaluationFailure(
            ResponseEntity<PersonController.ErrorResponse> response
    ) {
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        PersonController.ErrorResponse body = Objects.requireNonNull(response.getBody());
        assertEquals("STATE_EVALUATION_FAILED", body.status());
        assertEquals(
                "The configured language model could not evaluate the event",
                body.message()
        );
    }
}
