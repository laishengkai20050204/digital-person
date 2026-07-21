package com.laishengkai.digitalperson.state;

import java.util.Objects;

/** Immutable state view suitable for LLM evaluation and API responses. */
public record PersonStateSnapshot(
        double valence,
        double energy,
        double tension,
        double focus,
        double mentalLoad,
        double motivation,
        double fatigue,
        double sleepiness,
        double hunger,
        double loneliness,
        double socialNeed
) {
    public PersonStateSnapshot {
        requireRange("valence", valence, -1.0, 1.0);
        requireRange("energy", energy, 0.0, 1.0);
        requireRange("tension", tension, 0.0, 1.0);
        requireRange("focus", focus, 0.0, 1.0);
        requireRange("mentalLoad", mentalLoad, 0.0, 1.0);
        requireRange("motivation", motivation, 0.0, 1.0);
        requireRange("fatigue", fatigue, 0.0, 1.0);
        requireRange("sleepiness", sleepiness, 0.0, 1.0);
        requireRange("hunger", hunger, 0.0, 1.0);
        requireRange("loneliness", loneliness, 0.0, 1.0);
        requireRange("socialNeed", socialNeed, 0.0, 1.0);
    }

    public static PersonStateSnapshot from(PersonState state) {
        PersonState requestedState = Objects.requireNonNull(state, "state cannot be null");
        return new PersonStateSnapshot(
                requestedState.getAffectState().getValence(),
                requestedState.getAffectState().getEnergy(),
                requestedState.getAffectState().getTension(),
                requestedState.getCognitiveState().getFocus(),
                requestedState.getCognitiveState().getMentalLoad(),
                requestedState.getCognitiveState().getMotivation(),
                requestedState.getPhysicalState().getFatigue(),
                requestedState.getPhysicalState().getSleepiness(),
                requestedState.getPhysicalState().getHunger(),
                requestedState.getSocialState().getLoneliness(),
                requestedState.getSocialState().getSocialNeed()
        );
    }

    private static void requireRange(String name, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " must be a finite value between " + min + " and " + max
            );
        }
    }
}
