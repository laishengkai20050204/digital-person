package com.laishengkai.digitalperson.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the temporary token-protected person HTTP API. */
@ConfigurationProperties(prefix = "digital-person.person-api")
public record PersonApiProperties(boolean enabled, String token) {

    public PersonApiProperties {
        token = token == null ? "" : token.strip();
    }

    public String requiredToken() {
        if (token.isEmpty()) {
            throw new IllegalStateException(
                    "missing required configuration property: digital-person.person-api.token"
            );
        }
        return token;
    }

    @Override
    public String toString() {
        return "PersonApiProperties[enabled=" + enabled + ", token=<redacted>]";
    }
}
