package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateUpdaterTest {

    private static final double EPSILON = 1.0e-12;
    private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");

    @Test
    void callsEvaluatorOnceForNewEventAndReusesItsShape() {
        PersonState state = stateWithHunger(0.7);
        PersonEvent eating = event(ActivityType.EAT, "吃饭");
        AtomicInteger calls = new AtomicInteger();

        StateUpdater updater = new StateUpdater((currentState, newEvent) -> {
            calls.incrementAndGet();
            assertEquals(eating, newEvent);
            return List.of(
                    new StateTransition(StateDimension.HUNGER, -1.0)
            );
        });

        updater.update(state, List.of(eating), Duration.ZERO);

        assertEquals(1, calls.get());
        assertEquals(0.7, state.getPhysicalState().getHunger(), EPSILON);

        updater.update(state, List.of(eating), Duration.ofMinutes(30));

        assertEquals(1, calls.get());
        assertEquals(
                0.7 * Math.exp(-0.5),
                state.getPhysicalState().getHunger(),
                EPSILON
        );
    }

    @Test
    void changingOneChannelReevaluatesOnlyThatChannel() {
        PersonState state = PersonState.baseline();
        PersonEvent studying = event(ActivityType.STUDY, "学习");
        PersonEvent calmMusic = event(ActivityType.LISTEN_MUSIC, "舒缓音乐");
        PersonEvent distractingMusic = event(ActivityType.LISTEN_MUSIC, "吵闹音乐");
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger audioCalls = new AtomicInteger();

        StateUpdater updater = new StateUpdater((currentState, newEvent) -> {
            if (newEvent.equals(studying)) {
                primaryCalls.incrementAndGet();
                return List.of(
                        new StateTransition(StateDimension.FOCUS, 0.8)
                );
            }

            audioCalls.incrementAndGet();
            double shape = newEvent.equals(calmMusic) ? 0.2 : -0.4;
            return List.of(
                    new StateTransition(StateDimension.FOCUS, shape)
            );
        });

        updater.update(
                state,
                List.of(studying, calmMusic),
                Duration.ZERO
        );
        updater.update(
                state,
                List.of(studying, distractingMusic),
                Duration.ofHours(1)
        );
        updater.update(
                state,
                List.of(studying, distractingMusic),
                Duration.ofHours(1)
        );

        double afterOldEffects = 1.0 - 0.5 * Math.exp(-1.0);
        double expected = 1.0
                - (1.0 - afterOldEffects) * Math.exp(-0.4);

        assertEquals(1, primaryCalls.get());
        assertEquals(2, audioCalls.get());
        assertEquals(
                expected,
                state.getCognitiveState().getFocus(),
                EPSILON
        );
    }

    @Test
    void removingEventClearsItsEffectWithoutCallingEvaluatorAgain() {
        PersonState state = stateWithHunger(0.7);
        PersonEvent eating = event(ActivityType.EAT, "吃饭");
        AtomicInteger calls = new AtomicInteger();
        StateUpdater updater = new StateUpdater((currentState, newEvent) -> {
            calls.incrementAndGet();
            return List.of(
                    new StateTransition(StateDimension.HUNGER, -1.0)
            );
        });

        updater.update(state, List.of(eating), Duration.ZERO);
        updater.update(state, List.of(), Duration.ofMinutes(30));
        double hungerAfterRemoval = state.getPhysicalState().getHunger();
        updater.update(state, List.of(), Duration.ofMinutes(30));

        assertEquals(1, calls.get());
        assertEquals(
                0.7 * Math.exp(-0.5),
                hungerAfterRemoval,
                EPSILON
        );
        assertEquals(
                hungerAfterRemoval,
                state.getPhysicalState().getHunger(),
                EPSILON
        );
    }

    @Test
    void rejectsMultipleCurrentEventsInSameChannel() {
        PersonEvent studying = event(ActivityType.STUDY, "学习");
        PersonEvent eating = event(ActivityType.EAT, "吃饭");
        StateUpdater updater = new StateUpdater(
                (state, event) -> List.of()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> updater.update(
                        PersonState.baseline(),
                        List.of(studying, eating),
                        Duration.ZERO
                )
        );
    }

    @Test
    void rejectsInvalidInputsAndNullEvaluatorResult() {
        assertThrows(
                NullPointerException.class,
                () -> new StateUpdater(null)
        );

        StateUpdater updater = new StateUpdater((state, event) -> null);
        PersonEvent event = event(ActivityType.STUDY, "学习");

        assertThrows(
                NullPointerException.class,
                () -> updater.update(
                        PersonState.baseline(),
                        List.of(event),
                        Duration.ZERO
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

    private static PersonEvent event(ActivityType type, String title) {
        return new PersonEvent(
                type,
                title,
                "",
                TimeRange.openEnded(NOW)
        );
    }

    private static PersonState stateWithHunger(double hunger) {
        return new PersonState(
                new AffectState(0.0, 0.5, 0.0),
                CognitiveState.baseline(),
                new PhysicalState(0.0, 0.0, hunger),
                SocialState.baseline()
        );
    }
}
