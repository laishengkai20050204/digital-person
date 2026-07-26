package com.laishengkai.digitalperson.memory;

import java.util.Objects;
import java.util.Set;

/** Typed filters produced from one natural-language memory request. */
public record StructuredMemoryQueryPlan(
        Set<MemorySection> sections,
        Set<String> domains,
        Set<String> predicates,
        Set<MemoryEntityType> entityTypes,
        String entityMention
) {
    public StructuredMemoryQueryPlan {
        sections = copy(sections, "sections");
        domains = copyText(domains, "domains");
        predicates = copyText(predicates, "predicates");
        entityTypes = copy(entityTypes, "entityTypes");
        entityMention = Objects.requireNonNullElse(entityMention, "").strip();
    }

    private static <T> Set<T> copy(Set<T> values, String fieldName) {
        Set<T> safe = Set.copyOf(Objects.requireNonNullElse(values, Set.of()));
        if (safe.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(fieldName + " cannot contain null");
        }
        return safe;
    }

    private static Set<String> copyText(Set<String> values, String fieldName) {
        Set<String> safe = copy(values, fieldName);
        return safe.stream()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
