package com.laishengkai.digitalperson.infrastructure.langchain4j;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Global admission-control settings shared by every model invocation entry point. */
@ConfigurationProperties(prefix = "digital-person.llm.concurrency")
public record LanguageModelConcurrencyProperties(
        int maximum,
        Duration acquireTimeout
) {
    private static final int DEFAULT_MAXIMUM = 4;
    private static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(2);

    public LanguageModelConcurrencyProperties {
        maximum = maximum == 0 ? DEFAULT_MAXIMUM : maximum;
        acquireTimeout = acquireTimeout == null
                ? DEFAULT_ACQUIRE_TIMEOUT
                : acquireTimeout;
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        if (acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("acquireTimeout cannot be negative");
        }
    }
}
