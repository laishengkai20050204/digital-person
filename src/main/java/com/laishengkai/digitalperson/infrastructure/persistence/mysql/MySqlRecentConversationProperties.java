package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** MySQL retention and episode-retrieval policy for persisted conversation data. */
@ConfigurationProperties(prefix = "digital-person.persistence.mysql.conversation")
public record MySqlRecentConversationProperties(
        Integer retentionTurns,
        Boolean episodeEnabled,
        Integer episodeMaxItems
) {
    private static final int DEFAULT_RETENTION_TURNS = 500;
    private static final boolean DEFAULT_EPISODE_ENABLED = true;
    private static final int DEFAULT_EPISODE_MAX_ITEMS = 3;

    @ConstructorBinding
    public MySqlRecentConversationProperties(
            Integer retentionTurns,
            Boolean episodeEnabled,
            Integer episodeMaxItems
    ) {
        this.retentionTurns = positiveOrDefault(
                retentionTurns,
                DEFAULT_RETENTION_TURNS,
                "retentionTurns"
        );
        this.episodeEnabled = episodeEnabled == null
                ? DEFAULT_EPISODE_ENABLED
                : episodeEnabled;
        this.episodeMaxItems = positiveOrDefault(
                episodeMaxItems,
                DEFAULT_EPISODE_MAX_ITEMS,
                "episodeMaxItems"
        );
    }

    /** Compatibility constructor for callers that only configure raw-turn retention. */
    public MySqlRecentConversationProperties(Integer retentionTurns) {
        this(retentionTurns, null, null);
    }

    private static int positiveOrDefault(Integer value, int defaultValue, String fieldName) {
        int normalized = value == null ? defaultValue : value;
        if (normalized <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return normalized;
    }
}
