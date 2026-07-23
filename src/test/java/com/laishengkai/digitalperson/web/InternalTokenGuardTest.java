package com.laishengkai.digitalperson.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalTokenGuardTest {

    @Test
    void acceptsOnlyTheConfiguredToken() {
        InternalTokenGuard guard = new InternalTokenGuard("expected-token");

        assertTrue(guard.matches("expected-token"));
        assertFalse(guard.matches("wrong-token"));
        assertFalse(guard.matches(null));
        assertDoesNotThrow(() -> guard.requireAuthorized("expected-token"));
        assertThrows(
                InvalidInternalTokenException.class,
                () -> guard.requireAuthorized("wrong-token")
        );
    }

    @Test
    void rejectsBlankConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new InternalTokenGuard("   ")
        );
    }
}
