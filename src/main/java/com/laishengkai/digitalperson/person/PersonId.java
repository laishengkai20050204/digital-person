package com.laishengkai.digitalperson.person;

import java.util.Objects;
import java.util.UUID;

public record PersonId(UUID value) {
    public PersonId {
        Objects.requireNonNull(value, "value cannot be null");
    }

    public static PersonId random() {
        return new PersonId(UUID.randomUUID());
    }

    public static PersonId parse(String value) {
        return new PersonId(UUID.fromString(
                Objects.requireNonNull(value, "value cannot be null")
        ));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
