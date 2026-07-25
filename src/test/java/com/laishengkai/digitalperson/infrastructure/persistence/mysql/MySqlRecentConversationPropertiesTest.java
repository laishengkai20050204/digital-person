package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySqlRecentConversationPropertiesTest {

    @Test
    void appliesEpisodeDefaultsAndPreservesCompatibilityConstructor() {
        MySqlRecentConversationProperties properties =
                new MySqlRecentConversationProperties(600);

        assertThat(properties.retentionTurns()).isEqualTo(600);
        assertThat(properties.episodeEnabled()).isTrue();
        assertThat(properties.episodeMaxItems()).isEqualTo(3);
    }

    @Test
    void acceptsExplicitEpisodeSettings() {
        MySqlRecentConversationProperties properties =
                new MySqlRecentConversationProperties(500, false, 5);

        assertThat(properties.episodeEnabled()).isFalse();
        assertThat(properties.episodeMaxItems()).isEqualTo(5);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new MySqlRecentConversationProperties(0, true, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MySqlRecentConversationProperties(500, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
