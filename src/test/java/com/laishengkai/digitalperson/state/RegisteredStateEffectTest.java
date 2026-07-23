package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.EventId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisteredStateEffectTest {
    private static final Instant START = Instant.parse("2026-07-23T00:00:00Z");

    @Test
    void eventBoundEffectStopsAtSourceEventEnd() {
        EventId eventId = EventId.random();
        RegisteredStateEffect effect = RegisteredStateEffect.fromDraft(
                StateEffectDraft.eventBound(
                        StateEffectType.COGNITIVE,
                        "学习过程持续增加心理负担",
                        List.of(new StateTransition(StateDimension.MENTAL_LOAD, 0.4))
                ),
                eventId,
                START
        );
        Instant eventEnd = START.plus(Duration.ofHours(1));

        assertTrue(effect.isActiveAt(START.plusSeconds(3599), Map.of(eventId, eventEnd)));
        assertFalse(effect.isActiveAt(eventEnd, Map.of(eventId, eventEnd)));
        assertEquals(eventEnd, effect.effectiveEndTime(Map.of(eventId, eventEnd)).orElseThrow());
    }

    @Test
    void fixedTimeEffectCanExistWithoutSourceEvent() {
        RegisteredStateEffect effect = new RegisteredStateEffect(
                EffectId.random(),
                null,
                StateEffectType.PHYSICAL,
                "睡眠不足造成持续疲劳",
                START,
                StateEffectEndPolicy.FIXED_TIME,
                START.plus(Duration.ofHours(8)),
                List.of(new StateTransition(StateDimension.FATIGUE, 0.5))
        );

        assertTrue(effect.sourceEvent().isEmpty());
        assertTrue(effect.isActiveAt(START.plus(Duration.ofHours(7)), Map.of()));
        assertFalse(effect.isActiveAt(START.plus(Duration.ofHours(8)), Map.of()));
    }

    @Test
    void combinedPolicyUsesEarlierEventOrFixedBoundary() {
        EventId eventId = EventId.random();
        RegisteredStateEffect effect = new RegisteredStateEffect(
                EffectId.random(),
                eventId,
                StateEffectType.EMOTIONAL,
                "交流中的紧张感",
                START,
                StateEffectEndPolicy.EVENT_END_OR_FIXED_TIME,
                START.plus(Duration.ofHours(3)),
                List.of(new StateTransition(StateDimension.TENSION, 0.4))
        );
        Instant eventEnd = START.plus(Duration.ofHours(1));

        assertEquals(eventEnd, effect.effectiveEndTime(Map.of(eventId, eventEnd)).orElseThrow());
    }

    @Test
    void validatesCauseLifecycleAndTypeSpecificDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StateEffectDraft.eventBound(
                        StateEffectType.EMOTIONAL,
                        "   ",
                        List.of(new StateTransition(StateDimension.VALENCE, -0.2))
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> StateEffectDraft.eventBound(
                        StateEffectType.EMOTIONAL,
                        "错误类型",
                        List.of(new StateTransition(StateDimension.LONELINESS, 0.2))
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegisteredStateEffect(
                        EffectId.random(),
                        null,
                        StateEffectType.EMOTIONAL,
                        "缺少来源事件",
                        START,
                        StateEffectEndPolicy.EVENT_END,
                        null,
                        List.of(new StateTransition(StateDimension.VALENCE, -0.2))
                )
        );
    }
}
