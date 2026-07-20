package com.laishengkai.digitalperson;

import java.util.Objects;

public final class Personality {

    private final String description;

    public Personality(String description) {
        this.description = Objects.requireNonNull(description, "description cannot be null");
    }

    public String getDescription() {
        return description;
    }
}
