package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.person.PersonId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the OpenAI-compatible adapter used by OpenClaw. */
@ConfigurationProperties(prefix = "digital-person.openai-compat")
public record OpenAiCompatibilityProperties(
        boolean enabled,
        String personId,
        String model
) {
    public OpenAiCompatibilityProperties {
        personId = normalize(personId);
        model = normalize(model);
    }

    public PersonId requiredPersonId() {
        if (personId.isEmpty()) {
            throw new IllegalStateException(
                    "missing required configuration property: digital-person.openai-compat.person-id"
            );
        }
        return PersonId.parse(personId);
    }

    public String requiredModel() {
        if (model.isEmpty()) {
            throw new IllegalStateException(
                    "missing required configuration property: digital-person.openai-compat.model"
            );
        }
        return model;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
