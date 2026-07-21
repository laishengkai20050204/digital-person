package com.laishengkai.digitalperson.infrastructure.langchain4j;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jModelConfigTest {

    @Test
    void shouldLoadOpenAiCompatibleConfigurationFromEnvironment() {
        LangChain4jModelConfig config = LangChain4jModelConfig.fromEnvironment(Map.of(
                "LLM_BASE_URL", "https://openrouter.ai/api/v1",
                "LLM_API_KEY", "secret-key",
                "LLM_MODEL", "provider/model",
                "LLM_TIMEOUT_SECONDS", "45",
                "LLM_MAX_RETRIES", "3"
        ));

        assertEquals(URI.create("https://openrouter.ai/api/v1"), config.baseUrl());
        assertEquals("secret-key", config.apiKey());
        assertEquals("provider/model", config.modelName());
        assertEquals(Duration.ofSeconds(45), config.timeout());
        assertEquals(3, config.maxRetries());
    }

    @Test
    void shouldUseSafeDefaultsAndRedactApiKey() {
        LangChain4jModelConfig config = LangChain4jModelConfig.fromEnvironment(Map.of(
                "LLM_BASE_URL", "https://example.com/v1",
                "LLM_API_KEY", "never-log-this-key",
                "LLM_MODEL", "test-model"
        ));

        assertEquals(Duration.ofSeconds(60), config.timeout());
        assertEquals(2, config.maxRetries());
        assertTrue(config.toString().contains("<redacted>"));
        assertFalse(config.toString().contains("never-log-this-key"));
    }

    @Test
    void shouldRejectMissingRequiredEnvironmentVariable() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> LangChain4jModelConfig.fromEnvironment(Map.of(
                        "LLM_BASE_URL", "https://example.com/v1",
                        "LLM_MODEL", "test-model"
                ))
        );

        assertTrue(error.getMessage().contains("LLM_API_KEY"));
    }

    @Test
    void shouldBuildLangChain4jClientWithoutMakingNetworkRequest() {
        LangChain4jModelConfig config = new LangChain4jModelConfig(
                URI.create("https://openrouter.ai/api/v1"),
                "test-key",
                "provider/model",
                Duration.ofSeconds(5),
                0
        );

        assertDoesNotThrow(() -> new LangChain4jLanguageModel(config));
    }
}
