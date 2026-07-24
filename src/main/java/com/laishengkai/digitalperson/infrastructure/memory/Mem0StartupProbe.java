package com.laishengkai.digitalperson.infrastructure.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Checks Mem0 once during application startup without installing infrastructure. */
final class Mem0StartupProbe implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(Mem0StartupProbe.class);

    private final Mem0HttpClient client;
    private final Mem0Properties properties;

    Mem0StartupProbe(Mem0HttpClient client, Mem0Properties properties) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean available = client.probe()
                    .toCompletableFuture()
                    .get(
                            properties.requestTimeout().plusMillis(500).toMillis(),
                            TimeUnit.MILLISECONDS
                    );
            if (available) {
                LOGGER.info(
                        "Mem0 memory service is available: baseUrl={}, retrievalEnabled={}, minimumRelevance={}",
                        properties.baseUrl(),
                        properties.retrievalEnabled(),
                        properties.minimumRelevance()
                );
                return;
            }
            handleUnavailable(null);
        } catch (Exception exception) {
            handleUnavailable(exception);
        }
    }

    private void handleUnavailable(Exception exception) {
        if (properties.required()) {
            throw new IllegalStateException(
                    "required Mem0 memory service is unavailable at "
                            + properties.baseUrl(),
                    exception
            );
        }
        LOGGER.warn(
                "Mem0 memory service is unavailable; digital person will continue without Mem0: baseUrl={}",
                properties.baseUrl(),
                exception
        );
    }
}
