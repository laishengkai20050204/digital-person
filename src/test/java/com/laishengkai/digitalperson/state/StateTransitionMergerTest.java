package com.laishengkai.digitalperson.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateTransitionMergerTest {

    private final StateTransitionMerger merger = new StateTransitionMerger();

    @Test
    void capsCombinedShapeAtSupportedDomainLimit() {
        StateEffect first = () -> List.of(
                new StateTransition(StateDimension.ENERGY, 2.0)
        );
        StateEffect second = () -> List.of(
                new StateTransition(StateDimension.ENERGY, 2.5)
        );

        assertEquals(
                List.of(new StateTransition(
                        StateDimension.ENERGY,
                        StateTransition.MAX_ABSOLUTE_SHAPE
                )),
                merger.merge(List.of(first, second))
        );
    }

    @Test
    void removesDimensionsWhoseSignedEffectsCancelOut() {
        StateEffect positive = () -> List.of(
                new StateTransition(StateDimension.TENSION, 1.5)
        );
        StateEffect negative = () -> List.of(
                new StateTransition(StateDimension.TENSION, -1.5)
        );

        assertTrue(merger.merge(List.of(positive, negative)).isEmpty());
    }
}
