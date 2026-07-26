package com.laishengkai.digitalperson.memory;

import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredMemoryModelTest {
    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void normalizesDomainAndPredicateCodes() {
        StructuredMemoryFactDraft draft = new StructuredMemoryFactDraft(
                PersonId.random(),
                MemorySection.PREFERENCE,
                "game",
                "",
                "likes-most",
                "",
                "马超",
                "用户主要玩马超",
                0.9,
                0.8,
                null,
                null,
                NOW
        );

        assertThat(draft.domain()).isEqualTo("GAME");
        assertThat(draft.predicate()).isEqualTo("LIKES_MOST");
    }

    @Test
    void rejectsFactsWithoutAnyStructuredOrTextualValue() {
        assertThatThrownBy(() -> new StructuredMemoryFactDraft(
                PersonId.random(),
                MemorySection.PREFERENCE,
                "GAME",
                "",
                "LIKES",
                "",
                "",
                "空事实",
                0.9,
                0.8,
                null,
                null,
                NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain");
    }
}
