package com.laishengkai.digitalperson.infrastructure.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredMemoryExtractionPropertiesTest {

    @Test
    void usesConservativeDisabledDefaults() {
        StructuredMemoryExtractionProperties properties =
                new StructuredMemoryExtractionProperties(
                        null, null, null, null, null, null, null, null, null
                );

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.recentTurnsToKeep()).isEqualTo(2);
        assertThat(properties.batchTurns()).isEqualTo(8);
        assertThat(properties.minimumConfidence()).isEqualTo(0.70);
        assertThat(properties.minimumImportance()).isEqualTo(0.35);
        assertThat(properties.maxOutputTokens()).isEqualTo(4096);
    }

    @Test
    void rejectsInvalidThresholds() {
        assertThatThrownBy(() -> new StructuredMemoryExtractionProperties(
                true, 2, 8, 8, 12, 1.1, 0.35, 1200, 0.1
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
