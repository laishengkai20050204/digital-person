package com.laishengkai.digitalperson.person;

import java.util.Objects;

/**
 * One repository load result together with the persisted optimistic-lock version.
 */
public record VersionedPerson(Person person, long version) {
    public VersionedPerson {
        person = Objects.requireNonNull(person, "person cannot be null");
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
    }
}
