package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateUpdaterTest {

    private static final double EPSILON = 1.0e-12;
    private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");

    @Test
    void appliesTransitionsReturnedByEvaluator() {
        PersonState state = new PersonState(
                new AffectState(0.0, 0.5, 0.0),
                CognitiveState.baseline(),
                new PhysicalState(0.0, 0.0, 0.7),
                SocialState.baseline()
        );
        PersonEvent event = new PersonEvent(
                ActivityType.EAT,
                "吃饭",
                "",
                TimeRange.openEnded(NOW)
        );

        StateTransitionEvaluator evaluator = (currentState, recentEvents) -> {
            assertSame(state, currentState);
            assertEquals(List.of(event), recentEvents);

            return List.of(
                    new StateTransition(StateDimension.HUNGER, -1.0),
                    new StateTransition(StateDimension.VALENCE, 0.5)
            );
        };

        StateUpdater updater = new StateUpdater(evaluator);
        updater.update(
                state,
                List.of(event),
                Duration.ofMinutes(30)
        );

        assertEquals(
                0.7 * Math.exp(-0.5),
                state.getPhysicalState().getHunger(),
                EPSILON
        );
        assertEquals(
                1.0 - Math.exp(-0.25),
                state.getAffectState().getValence(),
                EPSILON
        );
    }

    @Test
    void emptyEvaluatorResultLeavesStateUnchanged() {
        PersonState state = new PersonState(
                new AffectState(0.2, 0.4, 0.3),
                new CognitiveState(0.4, 0.5, 0.6),
                new PhysicalState(0.3, 0.4, 0.5),
                new SocialState(0.6, 0.7)
        );
        StateUpdater updater = new StateUpdater(
                (currentState, recentEvents) -> List.of()
        );

        updater.update(state, List.of(), Duration.ofHours(1));

        assertEquals(0.2, state.getAffectState().getValence(), EPSILON);
        assertEquals(0.5, state.getPhysicalState().getHunger(), EPSILON);
        assertEquals(0.6, state.getSocialState().getLoneliness(), EPSILON);
    }

    @Test
    void zeroElapsedTimeDoesNotCallEvaluator() {
        AtomicBoolean called = new AtomicBoolean(false);
        StateUpdater updater = new StateUpdater((state, events) -> {
            called.set(true);
            return List.of(new StateTransition(StateDimension.HUNGER, -1.0));
        });

        updater.update(
                PersonState.baseline(),
                List.of(),
                Duration.ZERO
        );

        assertFalse(called.get());
    }

    @Test
    void rejectsInvalidInputsAndNullEvaluatorResult() {
        assertThrows(
                NullPointerException.class,
                () -> new StateUpdater(null)
        );

        StateUpdater updater = new StateUpdater((state, events) -> null);

        assertThrows(
                NullPointerException.class,
                () -> updater.update(
                        PersonState.baseline(),
                        List.of(),
                        Duration.ofMinutes(1)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> updater.update(
                        PersonState.baseline(),
                        List.of(),
                        Duration.ofMinutes(-1)
                )
        );
    }
}
