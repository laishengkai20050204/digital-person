package com.laishengkai.digitalperson.memory;

import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Objects;

/** Validated request for creating or refreshing a canonical entity. */
public record StructuredMemoryEntityDraft(
        PersonId personId,
        MemoryEntityType entityType,
        String canonicalName,
        String description,
        Instant observedAt
) {
    public StructuredMemoryEntityDraft {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        entityType = Objects.requireNonNull(entityType, "entityType cannot be null");
        canonicalName = Objects.requireNonNull(
                canonicalName,
                "canonicalName cannot be null"
        ).strip();
        if (canonicalName.isEmpty()) {
            throw new IllegalArgumentException("canonicalName cannot be blank");
        }
        if (canonicalName.length() > 255) {
            throw new IllegalArgumentException("canonicalName cannot exceed 255 characters");
        }
        description = Objects.requireNonNullElse(description, "").strip();
        observedAt = Objects.requireNonNull(observedAt, "observedAt cannot be null");
    }
}
