package com.laishengkai.digitalperson.infrastructure.langchain4j;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageModelPropertiesTest {

    @Test
    void shouldCreateLangChain4jConfigurationWhenEnabled() {
        LanguageModelProperties properties = new LanguageModelProperties(
                true,
                URI.create("https://openrouter.ai/api/v1"),
                "secret-key",
                "provider/model",
                Duration.ofSeconds(30),
                1,
                new LanguageModelProperties.ConnectionTest(true, "test-token")
        );

        LangChain4jModelConfig config = properties.toModelConfig();

        assertEquals(URI.create("https://openrouter.ai/api/v1"), config.baseUrl());
        assertEquals("secret-key", config.apiKey());
        assertEquals("provider/model", config.modelName());
        assertEquals(Duration.ofSeconds(30), config.timeout());
        assertEquals(1, config.maxRetries());
    }

    @Test
    void shouldRejectEnabledIntegrationWithoutApiKey() {
        LanguageModelProperties properties = new LanguageModelProperties(
                true,
                URI.create("https://openrouter.ai/api/v1"),
                "",
                "provider/model",
                Duration.ofSeconds(30),
                1,
                null
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                properties::toModelConfig
        );

        assertTrue(error.getMessage().contains("api-key"));
    }

    @Test
    void shouldRedactApiKeyAndConnectionTestToken() {
        LanguageModelProperties properties = new LanguageModelProperties(
                true,
                null,
                "never-log-api-key",
                "provider/model",
                null,
                0,
                new LanguageModelProperties.ConnectionTest(
                        true,
                        "never-log-test-token"
                )
        );

        String text = properties.toString();

        assertTrue(text.contains("<redacted>"));
        assertFalse(text.contains("never-log-api-key"));
        assertFalse(text.contains("never-log-test-token"));
    }
}
