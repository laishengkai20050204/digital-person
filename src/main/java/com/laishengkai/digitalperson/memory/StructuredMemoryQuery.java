package com.laishengkai.digitalperson.memory;

import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Whitelisted structured-memory filters; callers never submit raw SQL. */
public record StructuredMemoryQuery(
        PersonId personId,
        Set<MemorySection> sections,
        Set<String> domains,
        Set<String> entityIds,
        Set<String> predicates,
        Instant validAt,
        String relevanceQuery,
        int maxItems
) {
    public StructuredMemoryQuery {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        sections = Set.copyOf(Objects.requireNonNullElse(sections, Set.of()));
        if (sections.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("sections cannot contain null");
        }
        domains = normalizedCodes(domains, "domains");
        entityIds = normalizedText(entityIds, "entityIds");
        predicates = normalizedCodes(predicates, "predicates");
        validAt = Objects.requireNonNull(validAt, "validAt cannot be null");
        relevanceQuery = Objects.requireNonNullElse(relevanceQuery, "").strip();
        if (maxItems <= 0 || maxItems > 100) {
            throw new IllegalArgumentException("maxItems must be between 1 and 100");
        }
    }

    private static Set<String> normalizedCodes(Set<String> values, String fieldName) {
        Set<String> safe = Objects.requireNonNullElse(values, Set.of());
        if (safe.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(fieldName + " cannot contain null");
        }
        return safe.stream()
                .map(value -> StructuredMemoryFact.normalizeCode(value, fieldName))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> normalizedText(Set<String> values, String fieldName) {
        Set<String> safe = Objects.requireNonNullElse(values, Set.of());
        if (safe.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(fieldName + " cannot contain null");
        }
        return safe.stream()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
