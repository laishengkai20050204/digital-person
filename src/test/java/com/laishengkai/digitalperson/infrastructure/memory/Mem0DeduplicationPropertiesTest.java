package com.laishengkai.digitalperson.infrastructure.memory;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mem0DeduplicationPropertiesTest {

    @Test
    void compatibilityConstructionEnablesSafeDeduplicationDefaults() {
        Mem0Properties properties = new Mem0Properties(
                true,
                false,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertTrue(properties.deduplicationEnabled());
        assertEquals(0.62, properties.duplicateSemanticThreshold());
        assertEquals(0.30, properties.duplicateTextThreshold());
        assertEquals(5, properties.duplicateMaxCandidates());
        assertTrue(properties.extractionInstructions().contains("近义复述"));
    }

    @Test
    void rejectsInvalidDuplicateThresholdsAndCandidateLimits() {
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                1.01,
                0.30,
                5
        ));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                0.62,
                -0.01,
                5
        ));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                0.62,
                0.30,
                0
        ));
        assertThrows(IllegalArgumentException.class, () -> properties(
                true,
                0.62,
                0.30,
                21
        ));
    }

    private static Mem0Properties properties(
            boolean deduplicationEnabled,
            Double semanticThreshold,
            Double textThreshold,
            Integer maxCandidates
    ) {
        return new Mem0Properties(
                true,
                false,
                true,
                0.30,
                Mem0Properties.DEFAULT_EXTRACTION_INSTRUCTIONS,
                URI.create("http://127.0.0.1:8888"),
                "mem0-test-key",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                deduplicationEnabled,
                semanticThreshold,
                textThreshold,
                maxCandidates,
                Mem0Properties.DEFAULT_MAX_RESPONSE_BYTES,
                "/auth/setup-status"
        );
    }
}
