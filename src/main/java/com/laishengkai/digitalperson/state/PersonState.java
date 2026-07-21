package com.laishengkai.digitalperson.state;

import java.util.Objects;

public final class PersonState {

    private final AffectState affectState;

    public PersonState(AffectState affectState) {
        this.affectState = Objects.requireNonNull(
                affectState,
                "affectState cannot be null"
        );
    }

    public AffectState getAffectState() {
        return affectState;
    }
}
