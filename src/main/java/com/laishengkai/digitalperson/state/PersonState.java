package com.laishengkai.digitalperson.state;

import lombok.Getter;
import lombok.ToString;

import java.util.Objects;

/**
 * Aggregates the person's short-term internal state.
 */
@Getter
@ToString
public final class PersonState {

    private final AffectState affectState;
    private final CognitiveState cognitiveState;
    private final PhysicalState physicalState;
    private final SocialState socialState;

    /**
     * Keeps compatibility with callers that only provide emotional state.
     */
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
        this.affectState = Objects.requireNonNull(
                affectState,
                "affectState cannot be null"
        );
        this.cognitiveState = Objects.requireNonNull(
                cognitiveState,
                "cognitiveState cannot be null"
        );
        this.physicalState = Objects.requireNonNull(
                physicalState,
                "physicalState cannot be null"
        );
        this.socialState = Objects.requireNonNull(
                socialState,
                "socialState cannot be null"
        );
    }

    public static PersonState baseline() {
        return new PersonState(
                new AffectState(0.0, 0.5, 0.0),
                CognitiveState.baseline(),
                PhysicalState.baseline(),
                SocialState.baseline()
        );
    }
}
