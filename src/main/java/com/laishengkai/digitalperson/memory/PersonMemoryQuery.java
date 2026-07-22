package com.laishengkai.digitalperson.memory;

import com.laishengkai.digitalperson.person.PersonId;

import java.util.Objects;
import java.util.Set;

/** Provider-neutral request for memories relevant to one state evaluation. */
public record PersonMemoryQuery(
        PersonId personId,
        String relevanceQuery,
        Set<MemorySection> sections,
        int maxItems
) {
    public PersonMemoryQuery {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        relevanceQuery = Objects.requireNonNullElse(relevanceQuery, "").strip();
        sections = Set.copyOf(Objects.requireNonNullElse(
                sections,
                Set.of()
        ));
        if (sections.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("sections cannot contain null");
        }
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive");
        }
    }
}
