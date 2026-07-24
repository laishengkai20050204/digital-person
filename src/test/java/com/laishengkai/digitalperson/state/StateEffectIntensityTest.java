package com.laishengkai.digitalperson.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateEffectIntensityTest {

    @Test
    void resolvesDeterministicallyWithinEachTier() {
        for (StateEffectIntensity intensity : StateEffectIntensity.values()) {
            double first = intensity.resolve(StateEffectDirection.INCREASE, "person|event|dimension");
            double retry = intensity.resolve(StateEffectDirection.INCREASE, "person|event|dimension");

            assertEquals(first, retry);
            assertTrue(first >= intensity.minimumRate());
            assertTrue(first <= intensity.maximumRate());
        }
    }

    @Test
    void directionControlsTheSignWithoutChangingMagnitude() {
        double increase = StateEffectIntensity.HIGH.resolve(
                StateEffectDirection.INCREASE,
                "stable-seed"
        );
        double decrease = StateEffectIntensity.HIGH.resolve(
                StateEffectDirection.DECREASE,
                "stable-seed"
        );

        assertEquals(increase, -decrease);
        assertTrue(increase > 0.0);
        assertTrue(decrease < 0.0);
    }

    @Test
    void differentEventsReceiveStableButNonIdenticalJitter() {
        double first = StateEffectIntensity.MEDIUM.resolve(
                StateEffectDirection.INCREASE,
                "event-a"
        );
        double second = StateEffectIntensity.MEDIUM.resolve(
                StateEffectDirection.INCREASE,
                "event-b"
        );

        assertNotEquals(first, second);
    }

    @Test
    void instantTierUsesMinuteScaleRates() {
        double rate = StateEffectIntensity.INSTANT.resolve(
                StateEffectDirection.INCREASE,
                "instant-event"
        );

        assertTrue(rate >= 24.0 && rate <= 36.0);
    }
}
