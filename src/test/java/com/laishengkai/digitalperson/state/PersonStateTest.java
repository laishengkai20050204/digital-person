package com.laishengkai.digitalperson.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonStateTest {

    @Test
    void baselineContainsEveryStateGroup() {
        PersonState state = PersonState.baseline();

        assertEquals(0.0, state.getAffectState().getValence());
        assertEquals(0.5, state.getCognitiveState().getFocus());
        assertEquals(0.0, state.getPhysicalState().getFatigue());
        assertEquals(0.5, state.getSocialState().getSocialNeed());
    }

    @Test
    void emotionalOnlyConstructorProvidesOtherBaselineStates() {
        PersonState state = new PersonState(new AffectState(-0.3, 0.4, 0.6));

        assertEquals(-0.3, state.getAffectState().getValence());
        assertEquals(0.5, state.getCognitiveState().getMotivation());
        assertEquals(0.0, state.getPhysicalState().getHunger());
        assertEquals(0.0, state.getSocialState().getLoneliness());
    }

    @Test
    void stateGroupsCanChangeIndependently() {
        PersonState state = PersonState.baseline();

        state.getPhysicalState().setHunger(0.8);
        state.getCognitiveState().setMentalLoad(0.7);
        state.getSocialState().setLoneliness(0.6);

        assertEquals(0.8, state.getPhysicalState().getHunger());
        assertEquals(0.7, state.getCognitiveState().getMentalLoad());
        assertEquals(0.6, state.getSocialState().getLoneliness());
        assertEquals(0.0, state.getAffectState().getValence());
    }

    @Test
    void rejectsValuesOutsideUnitRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CognitiveState(1.1, 0.0, 0.5)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PhysicalState(0.0, -0.1, 0.0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SocialState(0.0, Double.NaN)
        );
    }
}
