package com.laishengkai.digitalperson.memory;

import java.time.Instant;
import java.util.Objects;

/** Model-proposed fact whose entity references are resolved before persistence. */
public record StructuredMemoryFactCandidate(
        MemorySection section,
        String domain,
        String subjectReference,
        String predicate,
        String objectReference,
        String textValue,
        String statement,
        double confidence,
        double importance,
        Instant validFrom,
        Instant validUntil,
        StructuredMemoryFactConflictMode conflictMode
) {
    public StructuredMemoryFactCandidate {
        section = Objects.requireNonNull(section, "section cannot be null");
        domain = StructuredMemoryFact.normalizeCode(domain, "domain");
        subjectReference = Objects.requireNonNullElse(subjectReference, "").strip();
        predicate = StructuredMemoryFact.normalizeCode(predicate, "predicate");
        objectReference = Objects.requireNonNullElse(objectReference, "").strip();
        textValue = Objects.requireNonNullElse(textValue, "").strip();
        statement = Objects.requireNonNull(statement, "statement cannot be null").strip();
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("statement cannot be blank");
        }
        if (statement.length() > 4_000) {
            throw new IllegalArgumentException("statement cannot exceed 4000 characters");
        }
        if (subjectReference.isEmpty() && objectReference.isEmpty() && textValue.isEmpty()) {
            throw new IllegalArgumentException(
                    "an extracted fact must contain an entity reference or text value"
            );
        }
        confidence = StructuredMemoryEntityCandidate.unitInterval(confidence, "confidence");
        importance = StructuredMemoryEntityCandidate.unitInterval(importance, "importance");
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom");
        }
        conflictMode = Objects.requireNonNullElse(
                conflictMode,
                StructuredMemoryFactConflictMode.KEEP_EXISTING
        );
    }
}
