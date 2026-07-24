package com.laishengkai.digitalperson.infrastructure.memory;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Optional dependency health that does not fail global health unless configured as required. */
final class Mem0HealthIndicator implements HealthIndicator {
    private static final Duration MAX_HEALTH_WAIT = Duration.ofSeconds(3);

    private final Mem0HttpClient client;
    private final Mem0Properties properties;

    Mem0HealthIndicator(Mem0HttpClient client, Mem0Properties properties) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    }

    @Override
    public Health health() {
        Duration wait = properties.requestTimeout().compareTo(MAX_HEALTH_WAIT) < 0
                ? properties.requestTimeout()
                : MAX_HEALTH_WAIT;
        try {
            boolean available = client.probe()
                    .toCompletableFuture()
                    .get(wait.toMillis(), TimeUnit.MILLISECONDS);
            if (available) {
                return Health.up()
                        .withDetail("available", true)
                        .withDetail("retrievalEnabled", properties.retrievalEnabled())
                        .build();
            }
            return unavailableHealth(null);
        } catch (Exception exception) {
            return unavailableHealth(exception);
        }
    }

    private Health unavailableHealth(Exception exception) {
        Health.Builder builder = properties.required()
                ? Health.down()
                : Health.up();
        builder.withDetail("available", false)
                .withDetail("required", properties.required());
        if (exception != null) {
            builder.withException(exception);
        }
        return builder.build();
    }
}
