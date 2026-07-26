package com.laishengkai.digitalperson.memory;

import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Objects;

/** One normalized fact stored in the structured memory database. */
public record StructuredMemoryFact(
        String factId,
        PersonId personId,
        MemorySection section,
        String domain,
        String subjectEntityId,
        String predicate,
        String objectEntityId,
        String textValue,
        String statement,
        double confidence,
        double importance,
        int evidenceCount,
        Instant validFrom,
        Instant validUntil,
        Instant lastConfirmedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public StructuredMemoryFact {
        factId = requireText(factId, "factId");
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        section = Objects.requireNonNull(section, "section cannot be null");
        domain = normalizeCode(domain, "domain");
        subjectEntityId = normalizeOptional(subjectEntityId);
        predicate = normalizeCode(predicate, "predicate");
        objectEntityId = normalizeOptional(objectEntityId);
        textValue = Objects.requireNonNullElse(textValue, "").strip();
        statement = requireText(statement, "statement");
        confidence = requireUnitInterval(confidence, "confidence");
        importance = requireUnitInterval(importance, "importance");
        if (evidenceCount <= 0) {
            throw new IllegalArgumentException("evidenceCount must be positive");
        }
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        lastConfirmedAt = Objects.requireNonNull(
                lastConfirmedAt,
                "lastConfirmedAt cannot be null"
        );
        createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
        }
    }

    public boolean validAt(Instant instant) {
        Instant time = Objects.requireNonNull(instant, "instant cannot be null");
        return (validFrom == null || !time.isBefore(validFrom))
                && (validUntil == null || time.isBefore(validUntil));
    }

    static String normalizeCode(String value, String fieldName) {
        String normalized = requireText(value, fieldName)
                .toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException(
                    fieldName + " must be an uppercase code with at most 64 characters"
            );
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return Objects.requireNonNullElse(value, "").strip();
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

    private static double requireUnitInterval(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    fieldName + " must be between 0.0 and 1.0"
            );
        }
        return value;
    }
}
