package com.laishengkai.digitalperson.infrastructure.dialogue;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime limits for the formal person dialogue flow. */
@ConfigurationProperties(prefix = "digital-person.dialogue")
public record PersonDialogueProperties(
        Integer maxMemoryItems,
        Integer maxConversationTurns,
        Integer maxOutputTokens,
        Double temperature,
        Boolean conversationSummaryEnabled,
        Integer conversationSummaryBatchTurns,
        Integer conversationSummaryMaxOutputTokens,
        Double conversationSummaryTemperature
) {
    private static final int DEFAULT_MAX_MEMORY_ITEMS = 8;
    private static final int DEFAULT_MAX_CONVERSATION_TURNS = 12;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_200;
    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final boolean DEFAULT_CONVERSATION_SUMMARY_ENABLED = true;
    private static final int DEFAULT_CONVERSATION_SUMMARY_BATCH_TURNS = 8;
    private static final int DEFAULT_CONVERSATION_SUMMARY_MAX_OUTPUT_TOKENS = 800;
    private static final double DEFAULT_CONVERSATION_SUMMARY_TEMPERATURE = 0.2;

    /** Compatibility constructor for callers that only configure reply generation. */
    public PersonDialogueProperties(
            Integer maxMemoryItems,
            Integer maxConversationTurns,
            Integer maxOutputTokens,
            Double temperature
    ) {
        this(
                maxMemoryItems,
                maxConversationTurns,
                maxOutputTokens,
                temperature,
                null,
                null,
                null,
                null
        );
    }

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
        temperature = temperatureOrDefault(
                temperature,
                DEFAULT_TEMPERATURE,
                "temperature"
        );
        conversationSummaryEnabled = conversationSummaryEnabled == null
                ? DEFAULT_CONVERSATION_SUMMARY_ENABLED
                : conversationSummaryEnabled;
        conversationSummaryBatchTurns = positiveOrDefault(
                conversationSummaryBatchTurns,
                DEFAULT_CONVERSATION_SUMMARY_BATCH_TURNS,
                "conversationSummaryBatchTurns"
        );
        conversationSummaryMaxOutputTokens = positiveOrDefault(
                conversationSummaryMaxOutputTokens,
                DEFAULT_CONVERSATION_SUMMARY_MAX_OUTPUT_TOKENS,
                "conversationSummaryMaxOutputTokens"
        );
        conversationSummaryTemperature = temperatureOrDefault(
                conversationSummaryTemperature,
                DEFAULT_CONVERSATION_SUMMARY_TEMPERATURE,
                "conversationSummaryTemperature"
        );
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

    private static double temperatureOrDefault(
            Double value,
            double defaultValue,
            String fieldName
    ) {
        double normalized = value == null ? defaultValue : value;
        if (!Double.isFinite(normalized) || normalized < 0.0 || normalized > 2.0) {
            throw new IllegalArgumentException(
                    fieldName + " must be between 0.0 and 2.0"
            );
        }
        return normalized;
    }
}
