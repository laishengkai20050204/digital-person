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
                null,
                null
        );

        assertEquals(0.30, properties.minimumRelevance());
        assertTrue(properties.extractionInstructions().contains("简体中文"));
        assertTrue(properties.extractionInstructions().contains("验证码"));
        assertTrue(properties.extractionInstructions().contains("不要记住或不要保存"));
        assertTrue(properties.baseUrl().toString().contains("127.0.0.1:8888"));
        assertTrue(properties.healthPath().startsWith("/"));
        assertEquals(Mem0Properties.DEFAULT_MAX_RESPONSE_BYTES, properties.maxResponseBytes());
        assertTrue(properties.toString().contains("<redacted>"));
        assertFalse(properties.toString().contains("never-log-this-key"));
    }

    @Test
    void preservesMandatoryGuardrailsWhenCustomInstructionsAreConfigured() {
        Mem0Properties properties = new Mem0Properties(
                true,
                false,
                false,
                0.0,
                "只保存与学习计划有关的稳定事实",
                null,
                "",
                null,
                null,
                null,
                null
        );

        assertEquals(0.0, properties.minimumRelevance());
        assertTrue(properties.extractionInstructions().contains("只保存与学习计划有关"));
        assertTrue(properties.extractionInstructions().contains("API Key"));
        assertTrue(properties.extractionInstructions().contains("附加提取要求"));
    }

    @Test
    void rejectsUnsafeResponseSizeConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new Mem0Properties(
                true,
                false,
                false,
                0.30,
                null,
                URI.create("http://127.0.0.1:8888"),
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                0,
                "/auth/setup-status"
        ));
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
                null,
                "/auth/setup-status"
        ));
    }
}
