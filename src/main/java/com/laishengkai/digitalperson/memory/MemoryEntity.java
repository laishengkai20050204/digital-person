package com.laishengkai.digitalperson.memory;

import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Objects;

/** Canonical entity referenced by one or more structured facts. */
public record MemoryEntity(
        String entityId,
        PersonId personId,
        MemoryEntityType entityType,
        String canonicalName,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
    public MemoryEntity {
        entityId = requireText(entityId, "entityId");
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        entityType = Objects.requireNonNull(entityType, "entityType cannot be null");
        canonicalName = requireText(canonicalName, "canonicalName");
        description = Objects.requireNonNullElse(description, "").strip();
        createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
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
