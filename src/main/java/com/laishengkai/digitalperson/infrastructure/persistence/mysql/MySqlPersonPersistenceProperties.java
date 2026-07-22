package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/** External configuration for the optional MySQL person repository. */
@ConfigurationProperties(prefix = "digital-person.persistence.mysql")
public record MySqlPersonPersistenceProperties(
        boolean enabled,
        String jdbcUrl,
        String username,
        String password,
        int maximumPoolSize,
        Duration connectionTimeout
) {
    private static final int DEFAULT_MAXIMUM_POOL_SIZE = 10;
    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(30);

    public MySqlPersonPersistenceProperties {
        jdbcUrl = normalize(jdbcUrl);
        username = normalize(username);
        password = password == null ? "" : password;
        maximumPoolSize = maximumPoolSize == 0
                ? DEFAULT_MAXIMUM_POOL_SIZE
                : maximumPoolSize;
        connectionTimeout = connectionTimeout == null
                ? DEFAULT_CONNECTION_TIMEOUT
                : connectionTimeout;
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize must be positive");
        }
        if (connectionTimeout.isZero() || connectionTimeout.isNegative()) {
            throw new IllegalArgumentException("connectionTimeout must be positive");
        }
    }

    public String requiredJdbcUrl() {
        String value = requireText(jdbcUrl, "digital-person.persistence.mysql.jdbc-url");
        if (!value.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("MySQL jdbc-url must start with jdbc:mysql://");
        }
        return value;
    }

    public String requiredUsername() {
        return requireText(username, "digital-person.persistence.mysql.username");
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

    @Override
    public String toString() {
        return "MySqlPersonPersistenceProperties[enabled="
                + enabled
                + ", jdbcUrl="
                + redactJdbcUrl(jdbcUrl)
                + ", username="
                + username
                + ", password=<redacted>, maximumPoolSize="
                + maximumPoolSize
                + ", connectionTimeout="
                + connectionTimeout
                + "]";
    }

    private static String redactJdbcUrl(String value) {
        String url = Objects.requireNonNullElse(value, "");
        int queryIndex = url.indexOf('?');
        return queryIndex < 0 ? url : url.substring(0, queryIndex) + "?<redacted>";
    }
}
