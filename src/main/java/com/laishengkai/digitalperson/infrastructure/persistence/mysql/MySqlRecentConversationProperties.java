package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MySQL retention policy for raw recent conversation turns. */
@ConfigurationProperties(prefix = "digital-person.persistence.mysql.conversation")
public record MySqlRecentConversationProperties(Integer retentionTurns) {
    private static final int DEFAULT_RETENTION_TURNS = 500;

    public MySqlRecentConversationProperties {
        retentionTurns = retentionTurns == null
                ? DEFAULT_RETENTION_TURNS
                : retentionTurns;
        if (retentionTurns <= 0) {
            throw new IllegalArgumentException("retentionTurns must be positive");
        }
    }
}
