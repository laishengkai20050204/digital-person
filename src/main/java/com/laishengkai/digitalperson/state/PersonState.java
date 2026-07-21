package com.laishengkai.digitalperson.state;

import java.util.Objects;

/** Aggregates the person's short-term internal state. */
public final class PersonState {
    private final AffectState affectState;
    private final CognitiveState cognitiveState;
    private final PhysicalState physicalState;
    private final SocialState socialState;

    public PersonState(AffectState affectState) {
        this(
                affectState,
                CognitiveState.baseline(),
                PhysicalState.baseline(),
                SocialState.baseline()
        );
    }

    public PersonState(
            AffectState affectState,
            CognitiveState cognitiveState,
            PhysicalState physicalState,
            SocialState socialState
    ) {
        this.affectState = Objects.requireNonNull(affectState, "affectState cannot be null");
        this.cognitiveState = Objects.requireNonNull(
                cognitiveState,
                "cognitiveState cannot be null"
        );
        this.physicalState = Objects.requireNonNull(
                physicalState,
                "physicalState cannot be null"
        );
        this.socialState = Objects.requireNonNull(socialState, "socialState cannot be null");
    }

    public static PersonState baseline() {
        return new PersonState(
                new AffectState(0.0, 0.5, 0.0),
                CognitiveState.baseline(),
                PhysicalState.baseline(),
                SocialState.baseline()
        );
    }

    public AffectState getAffectState() {
        return affectState;
    }

    public CognitiveState getCognitiveState() {
        return cognitiveState;
    }

    public PhysicalState getPhysicalState() {
        return physicalState;
    }

    public SocialState getSocialState() {
        return socialState;
    }

    public PersonState copy() {
        return new PersonState(
                affectState.copy(),
                cognitiveState.copy(),
                physicalState.copy(),
                socialState.copy()
        );
    }

    public PersonStateSnapshot snapshot() {
        return PersonStateSnapshot.from(this);
    }

    @Override
    public String toString() {
        return "PersonState[affectState=" + affectState
                + ", cognitiveState=" + cognitiveState
                + ", physicalState=" + physicalState
                + ", socialState=" + socialState + "]";
    }
}
