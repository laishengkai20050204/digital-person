package com.laishengkai.digitalperson.infrastructure.diagnostics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/** Configuration for prompt- and raw-output-exposing internal diagnostics. */
@ConfigurationProperties(prefix = "digital-person.diagnostics")
public record DiagnosticsProperties(boolean enabled, String token) {

    public DiagnosticsProperties {
        token = Objects.requireNonNullElse(token, "").strip();
    }

    public String requiredToken() {
        if (token.isEmpty()) {
            throw new IllegalStateException(
                    "missing required configuration property: digital-person.diagnostics.token"
            );
        }
        return token;
    }

    @Override
    public String toString() {
        return "DiagnosticsProperties[enabled=" + enabled + ", token=<redacted>]";
    }
}
