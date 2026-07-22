package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Manually verifies the configured model provider with one fixed, low-cost
 * request.
 *
 * <p>The endpoint is disabled by default and accepts no prompt from the caller.
 * A separate internal token prevents unauthenticated users from consuming model
 * quota.</p>
 */
@RestController
@RequestMapping("/internal/llm")
@ConditionalOnBean(LanguageModelGateway.class)
@ConditionalOnProperty(
        prefix = "digital-person.llm.connection-test",
        name = "enabled",
        havingValue = "true"
)
public final class LanguageModelConnectionTestController {
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private static final LanguageModelRequest CONNECTION_TEST_REQUEST =
            LanguageModelRequest.text(
                    "You are a connectivity test. Follow the user instruction exactly.",
                    "Reply with exactly: CONNECTION_OK"
            );

    private final LanguageModelGateway languageModelGateway;
    private final byte[] expectedToken;

    public LanguageModelConnectionTestController(
            LanguageModelGateway languageModelGateway,
            LanguageModelProperties properties
    ) {
        this.languageModelGateway = Objects.requireNonNull(
                languageModelGateway,
                "languageModelGateway cannot be null"
        );
        this.expectedToken = properties.connectionTest()
                .requiredToken()
                .getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/connection-test")
    public CompletionStage<ResponseEntity<ConnectionTestResponse>> testConnection(
            @RequestHeader(
                    name = INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken
    ) {
        if (!matchesExpectedToken(suppliedToken)) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(new ConnectionTestResponse(
                                    "UNAUTHORIZED",
                                    null
                            ))
            );
        }

        return languageModelGateway.invoke(CONNECTION_TEST_REQUEST)
                .handle((response, error) -> {
                    if (error != null) {
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                .body(new ConnectionTestResponse(
                                        "DOWN",
                                        null
                                ));
                    }

                    String modelText = response.text().strip();
                    if (!"CONNECTION_OK".equals(modelText)) {
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                .body(new ConnectionTestResponse(
                                        "UNEXPECTED_RESPONSE",
                                        modelText
                                ));
                    }

                    return ResponseEntity.ok(new ConnectionTestResponse(
                            "UP",
                            modelText
                    ));
                });
    }

    private boolean matchesExpectedToken(String suppliedToken) {
        if (suppliedToken == null) {
            return false;
        }
        byte[] suppliedBytes = suppliedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, suppliedBytes);
    }

    public record ConnectionTestResponse(
            String status,
            String modelResponse
    ) {
    }
}
