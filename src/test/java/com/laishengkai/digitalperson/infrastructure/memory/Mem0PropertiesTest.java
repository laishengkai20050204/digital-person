package com.laishengkai.digitalperson.infrastructure.memory;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mem0PropertiesTest {

    @Test
    void appliesSafeDefaultsAndRedactsApiKey() {
        Mem0Properties properties = new Mem0Properties(
                true,
                false,
                false,
                null,
                null,
                null,
                "never-log-this-key",
                null,
                null,
                null
        );

        assertEquals(0.30, properties.minimumRelevance());
        assertTrue(properties.extractionInstructions().contains("简体中文"));
        assertTrue(properties.baseUrl().toString().contains("127.0.0.1:8888"));
        assertTrue(properties.healthPath().startsWith("/"));
        assertTrue(properties.toString().contains("<redacted>"));
        assertFalse(properties.toString().contains("never-log-this-key"));
    }

    @Test
    void allowsExplicitZeroThreshold() {
        Mem0Properties properties = new Mem0Properties(
                true,
                false,
                false,
                0.0,
                "使用简体中文",
                null,
                "",
                null,
                null,
                null
        );

        assertEquals(0.0, properties.minimumRelevance());
    }

    @Test
    void rejectsRetrievalWhenIntegrationIsDisabled() {
        assertThrows(IllegalArgumentException.class, () -> new Mem0Properties(
                false,
                false,
                true,
                0.30,
                null,
                URI.create("http://127.0.0.1:8888"),
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "/auth/setup-status"
        ));
    }
}
