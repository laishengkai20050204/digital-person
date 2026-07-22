package com.laishengkai.digitalperson.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonApiPropertiesTest {

    @Test
    void normalizesAndRequiresTokenOnlyWhenUsed() {
        PersonApiProperties properties = new PersonApiProperties(true, "  secret  ");

        assertTrue(properties.enabled());
        assertEquals("secret", properties.requiredToken());
        assertEquals(
                "PersonApiProperties[enabled=true, token=<redacted>]",
                properties.toString()
        );
    }

    @Test
    void missingTokenFailsWithoutLeakingConfiguration() {
        PersonApiProperties properties = new PersonApiProperties(true, "  ");

        assertThrows(IllegalStateException.class, properties::requiredToken);
        assertTrue(properties.toString().contains("<redacted>"));
    }
}
