package com.laishengkai.digitalperson.infrastructure.dialogue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        Double conversationSummaryTemperature,
        Boolean conversationEpisodeEnabled,
        Integer conversationEpisodeMaxOutputTokens,
        Double conversationEpisodeTemperature
) {
    private static final int DEFAULT_MAX_MEMORY_ITEMS = 8;
    private static final int DEFAULT_MAX_CONVERSATION_TURNS = 12;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_200;
    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final boolean DEFAULT_CONVERSATION_SUMMARY_ENABLED = true;
    private static final int DEFAULT_CONVERSATION_SUMMARY_BATCH_TURNS = 8;
    private static final int DEFAULT_CONVERSATION_SUMMARY_MAX_OUTPUT_TOKENS = 800;
    private static final double DEFAULT_CONVERSATION_SUMMARY_TEMPERATURE = 0.2;
    private static final boolean DEFAULT_CONVERSATION_EPISODE_ENABLED = true;
    private static final int DEFAULT_CONVERSATION_EPISODE_MAX_OUTPUT_TOKENS = 700;
    private static final double DEFAULT_CONVERSATION_EPISODE_TEMPERATURE = 0.1;

    /** Explicitly selects the canonical constructor for Spring Boot configuration binding. */
    @ConstructorBinding
    public PersonDialogueProperties(
            Integer maxMemoryItems,
            Integer maxConversationTurns,
            Integer maxOutputTokens,
            Double temperature,
            Boolean conversationSummaryEnabled,
            Integer conversationSummaryBatchTurns,
            Integer conversationSummaryMaxOutputTokens,
            Double conversationSummaryTemperature,
            Boolean conversationEpisodeEnabled,
            Integer conversationEpisodeMaxOutputTokens,
            Double conversationEpisodeTemperature
    ) {
        this.maxMemoryItems = positiveOrDefault(
                maxMemoryItems,
                DEFAULT_MAX_MEMORY_ITEMS,
                "maxMemoryItems"
        );
        this.maxConversationTurns = positiveOrDefault(
                maxConversationTurns,
                DEFAULT_MAX_CONVERSATION_TURNS,
                "maxConversationTurns"
        );
        this.maxOutputTokens = positiveOrDefault(
                maxOutputTokens,
                DEFAULT_MAX_OUTPUT_TOKENS,
                "maxOutputTokens"
        );
        this.temperature = temperatureOrDefault(
                temperature,
                DEFAULT_TEMPERATURE,
                "temperature"
        );
        this.conversationSummaryEnabled = conversationSummaryEnabled == null
                ? DEFAULT_CONVERSATION_SUMMARY_ENABLED
                : conversationSummaryEnabled;
        this.conversationSummaryBatchTurns = positiveOrDefault(
                conversationSummaryBatchTurns,
                DEFAULT_CONVERSATION_SUMMARY_BATCH_TURNS,
                "conversationSummaryBatchTurns"
        );
        this.conversationSummaryMaxOutputTokens = positiveOrDefault(
                conversationSummaryMaxOutputTokens,
                DEFAULT_CONVERSATION_SUMMARY_MAX_OUTPUT_TOKENS,
                "conversationSummaryMaxOutputTokens"
        );
        this.conversationSummaryTemperature = temperatureOrDefault(
                conversationSummaryTemperature,
                DEFAULT_CONVERSATION_SUMMARY_TEMPERATURE,
                "conversationSummaryTemperature"
        );
        this.conversationEpisodeEnabled = conversationEpisodeEnabled == null
                ? DEFAULT_CONVERSATION_EPISODE_ENABLED
                : conversationEpisodeEnabled;
        this.conversationEpisodeMaxOutputTokens = positiveOrDefault(
                conversationEpisodeMaxOutputTokens,
                DEFAULT_CONVERSATION_EPISODE_MAX_OUTPUT_TOKENS,
                "conversationEpisodeMaxOutputTokens"
        );
        this.conversationEpisodeTemperature = temperatureOrDefault(
                conversationEpisodeTemperature,
                DEFAULT_CONVERSATION_EPISODE_TEMPERATURE,
                "conversationEpisodeTemperature"
        );
    }

    /** Compatibility constructor for callers that configure rolling summaries only. */
    public PersonDialogueProperties(
            Integer maxMemoryItems,
            Integer maxConversationTurns,
            Integer maxOutputTokens,
            Double temperature,
            Boolean conversationSummaryEnabled,
            Integer conversationSummaryBatchTurns,
            Integer conversationSummaryMaxOutputTokens,
            Double conversationSummaryTemperature
    ) {
        this(
                maxMemoryItems,
                maxConversationTurns,
                maxOutputTokens,
                temperature,
                conversationSummaryEnabled,
                conversationSummaryBatchTurns,
                conversationSummaryMaxOutputTokens,
                conversationSummaryTemperature,
                null,
                null,
                null
        );
    }

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
                null,
                null,
                null,
                null
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
