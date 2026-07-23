package com.laishengkai.digitalperson.state;

import java.util.Objects;
import java.util.UUID;

/** Stable identity for one independently managed state effect. */
public record EffectId(UUID value) implements Comparable<EffectId> {
    public EffectId {
        Objects.requireNonNull(value, "value cannot be null");
    }

    public static EffectId random() {
        return new EffectId(UUID.randomUUID());
    }

    public static EffectId parse(String value) {
        return new EffectId(UUID.fromString(Objects.requireNonNull(value, "value cannot be null")));
    }

    @Override
    public int compareTo(EffectId other) {
        return value.compareTo(Objects.requireNonNull(other, "other cannot be null").value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
