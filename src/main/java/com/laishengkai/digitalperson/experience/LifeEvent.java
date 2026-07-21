package com.laishengkai.digitalperson.experience;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;

@Getter
@ToString
@EqualsAndHashCode
public final class LifeEvent {

    private final String description;

    public LifeEvent(String description) {
        this.description = Objects.requireNonNull(description, "description cannot be null");
    }
}
