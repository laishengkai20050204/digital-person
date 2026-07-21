package com.laishengkai.digitalperson.experience;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeRangeTest {
    private static final Instant START = Instant.parse("2026-07-21T02:00:00Z");
    private static final Instant END = Instant.parse("2026-07-21T03:00:00Z");

    @Test
    void usesHalfOpenBoundary() {
        TimeRange range = TimeRange.closed(START, END);

        assertTrue(range.contains(START));
        assertTrue(range.contains(END.minusNanos(1)));
        assertFalse(range.contains(END));
    }

    @Test
    void adjacentRangesDoNotOverlap() {
        TimeRange first = TimeRange.closed(START, END);
        TimeRange second = TimeRange.closed(END, END.plusSeconds(3600));

        assertFalse(first.overlaps(second));
    }

    @Test
    void finishingOpenRangeReturnsNewImmutableValue() {
        TimeRange open = TimeRange.openEnded(START);
        TimeRange finished = open.finishAt(END);

        assertNotSame(open, finished);
        assertTrue(open.isOpenEnded());
        assertFalse(finished.isOpenEnded());
        assertThrows(
                IllegalStateException.class,
                () -> finished.finishAt(END.plusSeconds(3600))
        );
    }

    @Test
    void allFieldsAreFinal() {
        for (var field : TimeRange.class.getDeclaredFields()) {
            assertTrue(Modifier.isFinal(field.getModifiers()));
        }
    }
}
