package com.laishengkai.digitalperson.infrastructure.langchain4j;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Spring Boot configuration for the provider-neutral language-model gateway.
 *
 * <p>The defaults target OpenRouter, but callers can replace the base URL and
 * model identifier with any OpenAI-compatible provider. Secret values are
 * redacted from {@link #toString()}.</p>
 */
@ConfigurationProperties(prefix = "digital-person.llm")
public record LanguageModelProperties(
        boolean enabled,
        URI baseUrl,
        String apiKey,
        String model,
        Duration timeout,
        int maxRetries,
        ConnectionTest connectionTest
) {
    private static final URI DEFAULT_BASE_URL = URI.create(
            "https://openrouter.ai/api/v1"
    );
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    public LanguageModelProperties {
        baseUrl = validateBaseUrl(baseUrl == null ? DEFAULT_BASE_URL : baseUrl);
        apiKey = normalize(apiKey);
        model = normalize(model);
        timeout = validateTimeout(timeout == null ? DEFAULT_TIMEOUT : timeout);
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        connectionTest = connectionTest == null
                ? new ConnectionTest(false, "")
                : connectionTest;
    }

    /**
     * Converts bound Spring properties to the existing LangChain4j adapter
     * configuration after validating all settings required for a real call.
     */
    public LangChain4jModelConfig toModelConfig() {
        if (!enabled) {
            throw new IllegalStateException("language model integration is disabled");
        }

        return new LangChain4jModelConfig(
                baseUrl,
                requireText(apiKey, "digital-person.llm.api-key"),
                requireText(model, "digital-person.llm.model"),
                timeout,
                maxRetries
        );
    }

    @Override
    public String toString() {
        return "LanguageModelProperties[enabled="
                + enabled
                + ", baseUrl="
                + baseUrl
                + ", apiKey=<redacted>, model="
                + model
                + ", timeout="
                + timeout
                + ", maxRetries="
                + maxRetries
                + ", connectionTest="
                + connectionTest
                + "]";
    }

    /** Configuration for the manually triggered, token-protected smoke endpoint. */
    public record ConnectionTest(boolean enabled, String token) {
        public ConnectionTest {
            token = normalize(token);
        }

        public String requiredToken() {
            return requireText(
                    token,
                    "digital-person.llm.connection-test.token"
            );
        }

        @Override
        public String toString() {
            return "ConnectionTest[enabled=" + enabled + ", token=<redacted>]";
        }
    }

    private static URI validateBaseUrl(URI value) {
        URI uri = Objects.requireNonNull(value, "baseUrl cannot be null");
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("baseUrl must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUrl must contain a host");
        }
        return uri;
    }

    private static Duration validateTimeout(Duration value) {
        Duration duration = Objects.requireNonNull(value, "timeout cannot be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return duration;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static String requireText(String value, String propertyName) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalStateException(
                    "missing required configuration property: " + propertyName
            );
        }
        return normalized;
    }
}
