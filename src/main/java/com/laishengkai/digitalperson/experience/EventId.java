package com.laishengkai.digitalperson.experience;

import java.util.Objects;
import java.util.UUID;

public record EventId(UUID value) {

    public EventId {
        Objects.requireNonNull(value, "value cannot be null");
    }

    public static EventId random() {
        return new EventId(UUID.randomUUID());
    }

    public static EventId parse(String value) {
        return new EventId(UUID.fromString(
                Objects.requireNonNull(value, "value cannot be null")
        ));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
