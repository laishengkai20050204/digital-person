package com.laishengkai.digitalperson.infrastructure.memory;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

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
                "never-log-this-key",
                null,
                null,
                null
        );

        assertTrue(properties.baseUrl().toString().contains("127.0.0.1:8888"));
        assertTrue(properties.healthPath().startsWith("/"));
        assertTrue(properties.toString().contains("<redacted>"));
        assertFalse(properties.toString().contains("never-log-this-key"));
    }

    @Test
    void rejectsRetrievalWhenIntegrationIsDisabled() {
        assertThrows(IllegalArgumentException.class, () -> new Mem0Properties(
                false,
                false,
                true,
                URI.create("http://127.0.0.1:8888"),
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "/auth/setup-status"
        ));
    }
}
