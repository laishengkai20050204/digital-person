package com.laishengkai.digitalperson.memory;

import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Objects;

/** Validated write request for one canonical structured fact. */
public record StructuredMemoryFactDraft(
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
        Instant validFrom,
        Instant validUntil,
        Instant observedAt
) {
    public StructuredMemoryFactDraft {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        section = Objects.requireNonNull(section, "section cannot be null");
        domain = StructuredMemoryFact.normalizeCode(domain, "domain");
        subjectEntityId = Objects.requireNonNullElse(subjectEntityId, "").strip();
        predicate = StructuredMemoryFact.normalizeCode(predicate, "predicate");
        objectEntityId = Objects.requireNonNullElse(objectEntityId, "").strip();
        textValue = Objects.requireNonNullElse(textValue, "").strip();
        statement = Objects.requireNonNull(statement, "statement cannot be null").strip();
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("statement cannot be blank");
        }
        if (statement.length() > 4_000) {
            throw new IllegalArgumentException("statement cannot exceed 4000 characters");
        }
        if (subjectEntityId.isEmpty() && textValue.isEmpty() && objectEntityId.isEmpty()) {
            throw new IllegalArgumentException(
                    "a fact must contain a subject entity, object entity or text value"
            );
        }
        confidence = unitInterval(confidence, "confidence");
        importance = unitInterval(importance, "importance");
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt cannot be null");
    }

    private static double unitInterval(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    fieldName + " must be between 0.0 and 1.0"
            );
        }
        return value;
    }
}
