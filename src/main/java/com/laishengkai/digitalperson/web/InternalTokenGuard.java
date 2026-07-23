package com.laishengkai.digitalperson.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Constant-time verifier shared by token-protected HTTP boundaries. */
public final class InternalTokenGuard {
    private final byte[] expectedToken;

    public InternalTokenGuard(String requiredToken) {
        String token = Objects.requireNonNull(
                requiredToken,
                "requiredToken cannot be null"
        );
        if (token.isBlank()) {
            throw new IllegalArgumentException("requiredToken cannot be blank");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    public boolean matches(String suppliedToken) {
        return suppliedToken != null && MessageDigest.isEqual(
                expectedToken,
                suppliedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    public void requireAuthorized(String suppliedToken) {
        if (!matches(suppliedToken)) {
            throw new InvalidInternalTokenException();
        }
    }
}
