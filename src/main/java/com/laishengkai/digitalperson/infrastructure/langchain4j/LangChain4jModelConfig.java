package com.laishengkai.digitalperson.infrastructure.langchain4j;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for an OpenAI-compatible LangChain4j chat model.
 *
 * <p>The API key is never included in {@link #toString()}.</p>
 */
public final class LangChain4jModelConfig {
    public static final String BASE_URL_ENV = "LLM_BASE_URL";
    public static final String API_KEY_ENV = "LLM_API_KEY";
    public static final String MODEL_ENV = "LLM_MODEL";
    public static final String TIMEOUT_SECONDS_ENV = "LLM_TIMEOUT_SECONDS";
    public static final String MAX_RETRIES_ENV = "LLM_MAX_RETRIES";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int DEFAULT_MAX_RETRIES = 2;

    private final URI baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Duration timeout;
    private final int maxRetries;

    public LangChain4jModelConfig(
            URI baseUrl,
            String apiKey,
            String modelName,
            Duration timeout,
            int maxRetries
    ) {
        this.baseUrl = validateBaseUrl(baseUrl);
        this.apiKey = requireText(apiKey, "apiKey");
        this.modelName = requireText(modelName, "modelName");
        this.timeout = validateTimeout(timeout);
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        this.maxRetries = maxRetries;
    }

    /** Loads model configuration from the current process environment. */
    public static LangChain4jModelConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * Loads model configuration from a supplied environment map.
     *
     * <p>This overload keeps configuration parsing independently testable.</p>
     */
    public static LangChain4jModelConfig fromEnvironment(Map<String, String> environment) {
        Map<String, String> values = Objects.requireNonNull(
                environment,
                "environment cannot be null"
        );

        URI baseUrl = URI.create(requireEnvironment(values, BASE_URL_ENV));
        String apiKey = requireEnvironment(values, API_KEY_ENV);
        String modelName = requireEnvironment(values, MODEL_ENV);
        Duration timeout = Duration.ofSeconds(parseInteger(
                values,
                TIMEOUT_SECONDS_ENV,
                (int) DEFAULT_TIMEOUT.toSeconds()
        ));
        int maxRetries = parseInteger(values, MAX_RETRIES_ENV, DEFAULT_MAX_RETRIES);

        return new LangChain4jModelConfig(
                baseUrl,
                apiKey,
                modelName,
                timeout,
                maxRetries
        );
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String modelName() {
        return modelName;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    @Override
    public String toString() {
        return "LangChain4jModelConfig[baseUrl="
                + baseUrl
                + ", apiKey=<redacted>, modelName="
                + modelName
                + ", timeout="
                + timeout
                + ", maxRetries="
                + maxRetries
                + "]";
    }

    private static URI validateBaseUrl(URI value) {
        URI uri = Objects.requireNonNull(value, "baseUrl cannot be null");
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
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

    private static String requireEnvironment(
            Map<String, String> environment,
            String name
    ) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable: " + name);
        }
        return value.strip();
    }

    private static int parseInteger(
            Map<String, String> environment,
            String name,
            int defaultValue
    ) {
        String rawValue = environment.get(name);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(rawValue.strip());
        } catch (NumberFormatException error) {
            throw new IllegalStateException(
                    "environment variable must be an integer: " + name,
                    error
            );
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }
}
