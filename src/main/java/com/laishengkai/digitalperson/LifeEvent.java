package com.laishengkai.digitalperson;

import java.util.Objects;

public final class LifeEvent {

    private final String description;

    public LifeEvent(String description) {
        this.description = Objects.requireNonNull(description, "description cannot be null");
    }

    public String getDescription() {
        return description;
    }
}
