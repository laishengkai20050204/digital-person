package com.laishengkai.digitalperson.state;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateTransitionModelTest {

    private static final double EPSILON = 1.0e-12;

    private final StateTransitionModel model = new StateTransitionModel();

    @Test
    void negativeShapeContinuesFromCurrentPointTowardMinimum() {
        double next = model.calculate(
                StateDimension.HUNGER,
                0.7,
                -1.0,
                Duration.ofMinutes(30)
        );

        assertEquals(0.7 * Math.exp(-0.5), next, EPSILON);
    }

    @Test
    void positiveShapeContinuesFromCurrentPointTowardMaximum() {
        double next = model.calculate(
                StateDimension.HUNGER,
                0.7,
                1.0,
                Duration.ofMinutes(30)
        );

        assertEquals(1.0 - 0.3 * Math.exp(-0.5), next, EPSILON);
    }

    @Test
    void pollingFrequencyDoesNotChangeTheResult() {
        PersonState oneUpdate = stateWithHunger(0.7);
        PersonState twoUpdates = stateWithHunger(0.7);
        StateTransition transition = new StateTransition(
                StateDimension.HUNGER,
                -1.4
        );

        model.apply(oneUpdate, transition, Duration.ofHours(1));
        model.apply(twoUpdates, transition, Duration.ofMinutes(30));
        model.apply(twoUpdates, transition, Duration.ofMinutes(30));

        assertEquals(
                oneUpdate.getPhysicalState().getHunger(),
                twoUpdates.getPhysicalState().getHunger(),
                EPSILON
        );
    }

    @Test
    void supportsEveryStateDimension() {
        PersonState state = sampleState();
        Map<StateDimension, Double> before = new EnumMap<>(StateDimension.class);
        for (StateDimension dimension : StateDimension.values()) {
            before.put(dimension, dimension.read(state));
        }

        List<StateTransition> transitions = List.of(
                new StateTransition(StateDimension.VALENCE, 1.0),
                new StateTransition(StateDimension.ENERGY, 1.1),
                new StateTransition(StateDimension.TENSION, 1.2),
                new StateTransition(StateDimension.FOCUS, 1.3),
                new StateTransition(StateDimension.MENTAL_LOAD, 1.4),
                new StateTransition(StateDimension.MOTIVATION, 1.5),
                new StateTransition(StateDimension.FATIGUE, 1.6),
                new StateTransition(StateDimension.SLEEPINESS, 1.7),
                new StateTransition(StateDimension.HUNGER, 1.8),
                new StateTransition(StateDimension.LONELINESS, 1.9),
                new StateTransition(StateDimension.SOCIAL_NEED, 2.0)
        );

        model.applyAll(state, transitions, Duration.ofHours(1));

        for (StateTransition transition : transitions) {
            StateDimension dimension = transition.dimension();
            double original = before.get(dimension);
            double updated = dimension.read(state);

            assertTrue(updated > original);
            assertTrue(updated < dimension.getMaximum());
        }
    }

    @Test
    void valenceUsesItsFullMinusOneToOneRange() {
        double next = model.calculate(
                StateDimension.VALENCE,
                0.0,
                -1.0,
                Duration.ofHours(1)
        );

        double expected = -1.0 + Math.exp(-1.0);
        assertEquals(expected, next, EPSILON);
    }

    @Test
    void zeroElapsedTimeLeavesStateUnchanged() {
        PersonState state = stateWithHunger(0.7);

        model.apply(
                state,
                new StateTransition(StateDimension.HUNGER, -2.0),
                Duration.ZERO
        );

        assertEquals(0.7, state.getPhysicalState().getHunger(), EPSILON);
    }

    @Test
    void rejectsInvalidTransitionParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StateTransition(StateDimension.HUNGER, 0.0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> model.calculate(
                        StateDimension.HUNGER,
                        -0.1,
                        -1.0,
                        Duration.ofMinutes(1)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> model.calculate(
                        StateDimension.HUNGER,
                        0.5,
                        -1.0,
                        Duration.ofMinutes(-1)
                )
        );
    }

    @Test
    void rejectsMultipleTransitionsForTheSameDimension() {
        PersonState state = PersonState.baseline();

        assertThrows(
                IllegalArgumentException.class,
                () -> model.applyAll(
                        state,
                        List.of(
                                new StateTransition(StateDimension.HUNGER, -1.0),
                                new StateTransition(StateDimension.HUNGER, -2.0)
                        ),
                        Duration.ofMinutes(30)
                )
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

    private static PersonState sampleState() {
        return new PersonState(
                new AffectState(-0.4, 0.2, 0.3),
                new CognitiveState(0.2, 0.3, 0.4),
                new PhysicalState(0.2, 0.3, 0.4),
                new SocialState(0.2, 0.3)
        );
    }
}
