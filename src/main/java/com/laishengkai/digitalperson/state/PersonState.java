package com.laishengkai.digitalperson.state;

import lombok.Getter;
import lombok.ToString;

import java.util.Objects;

@Getter
@ToString
public final class PersonState {

    private final AffectState affectState;

    public PersonState(AffectState affectState) {
        this.affectState = Objects.requireNonNull(
                affectState,
                "affectState cannot be null"
        );
    }
}
