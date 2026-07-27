package com.laishengkai.digitalperson.memory;

import java.util.List;
import java.util.Objects;

/** Model-proposed canonical entity and aliases, referenced by a batch-local key. */
public record StructuredMemoryEntityCandidate(
        String reference,
        MemoryEntityType entityType,
        String canonicalName,
        List<String> aliases,
        String description,
        double confidence
) {
    public StructuredMemoryEntityCandidate {
        reference = required(reference, "reference", 64);
        entityType = Objects.requireNonNull(entityType, "entityType cannot be null");
        canonicalName = required(canonicalName, "canonicalName", 255);
        aliases = List.copyOf(Objects.requireNonNullElse(aliases, List.of())).stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(alias -> !alias.isEmpty())
                .distinct()
                .limit(8)
                .toList();
        description = Objects.requireNonNullElse(description, "").strip();
        confidence = unitInterval(confidence, "confidence");
    }

    private static String required(String value, String name, int maxLength) {
        String normalized = Objects.requireNonNull(value, name + " cannot be null").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " cannot exceed " + maxLength + " characters");
        }
        return normalized;
    }

    static double unitInterval(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0");
        }
        return value;
    }
}
