package com.laishengkai.digitalperson.infrastructure.dialogue;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime limits for the formal person dialogue flow. */
@ConfigurationProperties(prefix = "digital-person.dialogue")
public record PersonDialogueProperties(
        Integer maxMemoryItems,
        Integer maxConversationTurns,
        Integer maxOutputTokens,
        Double temperature
) {
    private static final int DEFAULT_MAX_MEMORY_ITEMS = 8;
    private static final int DEFAULT_MAX_CONVERSATION_TURNS = 12;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_200;
    private static final double DEFAULT_TEMPERATURE = 0.7;

    public PersonDialogueProperties {
        maxMemoryItems = positiveOrDefault(
                maxMemoryItems,
                DEFAULT_MAX_MEMORY_ITEMS,
                "maxMemoryItems"
        );
        maxConversationTurns = positiveOrDefault(
                maxConversationTurns,
                DEFAULT_MAX_CONVERSATION_TURNS,
                "maxConversationTurns"
        );
        maxOutputTokens = positiveOrDefault(
                maxOutputTokens,
                DEFAULT_MAX_OUTPUT_TOKENS,
                "maxOutputTokens"
        );
        temperature = temperature == null ? DEFAULT_TEMPERATURE : temperature;
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException(
                    "temperature must be between 0.0 and 2.0"
            );
        }
    }

    private static int positiveOrDefault(
            Integer value,
            int defaultValue,
            String fieldName
    ) {
        int normalized = value == null ? defaultValue : value;
        if (normalized <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return normalized;
    }
}
