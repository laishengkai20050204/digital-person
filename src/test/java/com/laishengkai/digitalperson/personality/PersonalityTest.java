package com.laishengkai.digitalperson.personality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonalityTest {

    @Test
    void exposesHexacoRecordAndLegacyBeanAccessorsConsistently() {
        Personality personality = new Personality(0.8, 0.7, 0.6, 0.5, 0.4, 0.9);

        assertEquals(personality.honestyHumility(), personality.getHonestyHumility());
        assertEquals(personality.emotionality(), personality.getEmotionality());
        assertEquals(personality.extraversion(), personality.getExtraversion());
        assertEquals(personality.agreeableness(), personality.getAgreeableness());
        assertEquals(personality.conscientiousness(), personality.getConscientiousness());
        assertEquals(personality.openness(), personality.getOpenness());
        assertEquals(
                personality,
                new Personality(0.8, 0.7, 0.6, 0.5, 0.4, 0.9)
        );
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Personality(Double.NaN, 0.5, 0.5, 0.5, 0.5, 0.5)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new Personality(0.5, -0.1, 0.5, 0.5, 0.5, 0.5)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 1.1)
        );
    }
}
