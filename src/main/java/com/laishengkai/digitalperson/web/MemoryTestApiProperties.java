package com.laishengkai.digitalperson.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the temporary token-protected Mem0 test API. */
@ConfigurationProperties(prefix = "digital-person.memory.test-api")
public record MemoryTestApiProperties(boolean enabled, String token) {

    public MemoryTestApiProperties {
        token = token == null ? "" : token.strip();
    }

    public String requiredToken() {
        if (token.isEmpty()) {
            throw new IllegalStateException(
                    "missing required configuration property: digital-person.memory.test-api.token"
            );
        }
        return token;
    }

    @Override
    public String toString() {
        return "MemoryTestApiProperties[enabled=" + enabled + ", token=<redacted>]";
    }
}
