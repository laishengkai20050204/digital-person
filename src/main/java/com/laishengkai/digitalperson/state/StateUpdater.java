package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.PersonEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Applies the first deterministic state-change rules for completed events.
 *
 * <p>The coefficients are simulation parameters rather than psychological facts.
 * They can be tuned later without changing the state model.
 */
public final class StateUpdater {

    /**
     * Applies one completed event exactly once to the supplied state.
     */
    public void applyFinishedEvent(PersonState state, PersonEvent event) {
        PersonState currentState = Objects.requireNonNull(
                state,
                "state cannot be null"
        );
        PersonEvent finishedEvent = Objects.requireNonNull(
                event,
                "event cannot be null"
        );

        if (!finishedEvent.isFinished()) {
            throw new IllegalArgumentException(
                    "state changes can only be settled from a finished event"
            );
        }

        Instant endTime = finishedEvent.getEndTime().orElseThrow(
                () -> new IllegalStateException(
                        "a finished event must have an end time"
                )
        );
        Duration duration = Duration.between(
                finishedEvent.getStartTime(),
                endTime
        );

        switch (finishedEvent.getActivityType()) {
            case STUDY, WORK -> applyStudyOrWork(currentState, scale(duration, 4.0));
            case EAT -> applyEating(currentState, scale(duration, 0.5));
            case SLEEP -> applySleep(currentState, scale(duration, 8.0));
            case REST -> applyRest(currentState, scale(duration, 2.0));
            case TRAVEL -> applyTravel(currentState, scale(duration, 3.0));
            case EXERCISE -> applyExercise(currentState, scale(duration, 1.5));
            case SOCIAL -> applySocial(currentState, scale(duration, 1.5));
            case CHAT -> applySocial(currentState, scale(duration, 1.0));
            case ENTERTAINMENT -> applyEntertainment(currentState, scale(duration, 2.0));
            case SHOPPING -> applyShopping(currentState, scale(duration, 2.0));
            case LISTEN_MUSIC -> applyMusic(currentState, scale(duration, 1.0));
            case OTHER -> {
                // Unknown activities have no predefined effect yet.
            }
        }
    }

    private static void applyStudyOrWork(PersonState state, double scale) {
        state.getAffectState().adjust(
                -0.05 * scale,
                -0.20 * scale,
                0.20 * scale
        );
        state.getCognitiveState().adjust(
                -0.15 * scale,
                0.35 * scale,
                -0.10 * scale
        );
        state.getPhysicalState().adjust(
                0.25 * scale,
                0.10 * scale,
                0.15 * scale
        );
    }

    private static void applyEating(PersonState state, double scale) {
        state.getAffectState().adjust(
                0.05 * scale,
                0.10 * scale,
                -0.03 * scale
        );
        state.getPhysicalState().adjust(
                0.0,
                0.0,
                -0.80 * scale
        );
    }

    private static void applySleep(PersonState state, double scale) {
        state.getAffectState().adjust(
                0.10 * scale,
                0.60 * scale,
                -0.25 * scale
        );
        state.getCognitiveState().adjust(
                0.30 * scale,
                -0.40 * scale,
                0.15 * scale
        );
        state.getPhysicalState().adjust(
                -0.80 * scale,
                -0.90 * scale,
                0.20 * scale
        );
    }

    private static void applyRest(PersonState state, double scale) {
        state.getAffectState().adjust(
                0.05 * scale,
                0.25 * scale,
                -0.20 * scale
        );
        state.getCognitiveState().adjust(
                0.10 * scale,
                -0.25 * scale,
                0.05 * scale
        );
        state.getPhysicalState().adjust(
                -0.30 * scale,
                -0.15 * scale,
                0.05 * scale
        );
    }

    private static void applyTravel(PersonState state, double scale) {
        state.getAffectState().adjust(
                0.0,
                -0.15 * scale,
                0.10 * scale
        );
        state.getCognitiveState().adjust(
                -0.10 * scale,
                0.10 * scale,
                0.0
        );
        state.getPhysicalState().adjust(
                0.25 * scale,
                0.05 * scale,
                0.10 * scale
        );
    }

    private static void applyExercise(PersonState state, double scale) {
        state.getAffectState().adjust(
                0.15 * scale,
                0.05 * scale,
                -0.20 * scale
        );
        state.getCognitiveState().adjust(
                0.05 * scale,
                -0.05 * scale,
                0.10 * scale
        );
        state.getPhysicalState().adjust(
                0.25 * scale,
                0.0,
                0.20 * scale
        );
    }

    private static void applySocial(PersonState state, double scale) {
        state.getAffectState().adjust(
                0.15 * scale,
                0.05 * scale,
                -0.10 * scale
        );
        state.getCognitiveState().adjust(
                0.0,
                -0.05 * scale,
                0.0
        );
        state.getSocialState().adjust(
                -0.40 * scale,
                -0.30 * scale
        );
    }

    private static void applyEntertainment(PersonState state, double scale) {
        state.getAffectState().adjust(
                0.15 * scale,
                0.0,
                -0.15 * scale
        );
        state.getCognitiveState().adjust(
                0.0,
                -0.15 * scale,
                0.0
        );
        state.getPhysicalState().adjust(
                0.05 * scale,
                0.05 * scale,
                0.05 * scale
        );
    }

    private static void applyShopping(PersonState state, double scale) {
        state.getAffectState().adjust(
                0.05 * scale,
                -0.10 * scale,
                0.05 * scale
        );
        state.getPhysicalState().adjust(
                0.15 * scale,
                0.0,
                0.05 * scale
        );
    }

    private static void applyMusic(PersonState state, double scale) {
        state.getAffectState().adjust(
                0.08 * scale,
                0.0,
                -0.12 * scale
        );
        state.getCognitiveState().adjust(
                0.0,
                -0.08 * scale,
                0.0
        );
    }

    private static double scale(Duration duration, double fullEffectHours) {
        double hours = duration.toSeconds() / 3600.0;
        double rawScale = hours / fullEffectHours;
        return Math.max(0.0, Math.min(1.0, rawScale));
    }
}
