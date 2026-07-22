package com.laishengkai.digitalperson.memory;

import java.util.List;
import java.util.Objects;

/** Immutable memory slice supplied to one model evaluation. */
public record PersonMemoryContext(
        MemoryAvailability availability,
        List<MemoryItem> items
) {
    public PersonMemoryContext {
        availability = Objects.requireNonNull(
                availability,
                "availability cannot be null"
        );
        items = List.copyOf(Objects.requireNonNull(items, "items cannot be null"));
        if (items.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("items cannot contain null");
        }
    }

    public static PersonMemoryContext disabled() {
        return new PersonMemoryContext(MemoryAvailability.DISABLED, List.of());
    }

    public static PersonMemoryContext available(List<MemoryItem> items) {
        return new PersonMemoryContext(MemoryAvailability.AVAILABLE, items);
    }
}
