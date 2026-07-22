package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LanguageModelConnectionTestControllerTest {

    @Test
    void shouldRejectIncorrectInternalTokenWithoutCallingModel() {
        LanguageModelGateway gateway = request -> {
            throw new AssertionError("model should not be called");
        };
        LanguageModelConnectionTestController controller = controller(
                gateway,
                "expected-token"
        );

        ResponseEntity<LanguageModelConnectionTestController.ConnectionTestResponse>
                response = controller.testConnection("wrong-token")
                .toCompletableFuture()
                .join();

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UNAUTHORIZED", response.getBody().status());
    }

    @Test
    void shouldReturnUpWhenOpenRouterStyleGatewayRespondsAsExpected() {
        LanguageModelGateway gateway = request ->
                CompletableFuture.completedFuture(
                        LanguageModelResponse.text("CONNECTION_OK")
                );
        LanguageModelConnectionTestController controller = controller(
                gateway,
                "expected-token"
        );

        ResponseEntity<LanguageModelConnectionTestController.ConnectionTestResponse>
                response = controller.testConnection("expected-token")
                .toCompletableFuture()
                .join();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().status());
        assertEquals("CONNECTION_OK", response.getBody().modelResponse());
    }

    @Test
    void shouldReturnBadGatewayWhenProviderCallFails() {
        LanguageModelGateway gateway = request ->
                CompletableFuture.failedFuture(
                        new IllegalStateException("provider unavailable")
                );
        LanguageModelConnectionTestController controller = controller(
                gateway,
                "expected-token"
        );

        ResponseEntity<LanguageModelConnectionTestController.ConnectionTestResponse>
                response = controller.testConnection("expected-token")
                .toCompletableFuture()
                .join();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().status());
    }

    private static LanguageModelConnectionTestController controller(
            LanguageModelGateway gateway,
            String token
    ) {
        LanguageModelProperties properties = new LanguageModelProperties(
                true,
                URI.create("https://openrouter.ai/api/v1"),
                "test-key",
                "provider/model",
                Duration.ofSeconds(5),
                0,
                new LanguageModelProperties.ConnectionTest(true, token)
        );
        return new LanguageModelConnectionTestController(gateway, properties);
    }
}
