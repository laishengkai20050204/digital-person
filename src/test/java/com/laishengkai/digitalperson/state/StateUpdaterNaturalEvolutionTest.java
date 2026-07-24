package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateUpdaterNaturalEvolutionTest {
    private static final PersonId PERSON_ID = PersonId.parse(
            "567f1d4e-2aab-427b-a4ca-dd69a00c06df"
    );
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final StateUpdater UPDATER = new StateUpdater();

    @Test
    void fourHoursOfSleepRecoverStateWithoutProducingFullRecovery() {
        Instant start = Instant.parse("2026-07-23T18:15:24Z");
        Instant end = start.plus(Duration.ofHours(4));
        PersonEvent sleep = event(ActivityType.SLEEP, "夜间睡眠", start, end);
        PersonState state = state(0.60, 0.18, 0.94, 0.80, 0.0);

        settle(state, List.of(sleep), start, end);

        PersonStateSnapshot result = state.snapshot();
        assertTrue(result.energy() > 0.60 && result.energy() < 0.90);
        assertTrue(result.fatigue() < 0.18 && result.fatigue() > 0.04);
        assertTrue(result.mentalLoad() < 0.94 && result.mentalLoad() > 0.08);
        assertTrue(result.sleepiness() < 0.80);
    }

    @Test
    void eightHoursOfSleepRecoverMoreThanFourHours() {
        Instant start = Instant.parse("2026-07-23T14:00:00Z");
        PersonState fourHourState = state(0.60, 0.40, 0.80, 0.75, 0.0);
        PersonState eightHourState = state(0.60, 0.40, 0.80, 0.75, 0.0);

        settle(
                fourHourState,
                List.of(event(ActivityType.SLEEP, "睡眠", start, start.plus(Duration.ofHours(4)))),
                start,
                start.plus(Duration.ofHours(4))
        );
        settle(
                eightHourState,
                List.of(event(ActivityType.SLEEP, "睡眠", start, start.plus(Duration.ofHours(8)))),
                start,
                start.plus(Duration.ofHours(8))
        );

        assertTrue(eightHourState.snapshot().energy() > fourHourState.snapshot().energy());
        assertTrue(eightHourState.snapshot().fatigue() < fourHourState.snapshot().fatigue());
        assertTrue(eightHourState.snapshot().mentalLoad() < fourHourState.snapshot().mentalLoad());
    }

    @Test
    void eatingNaturallyLowersHunger() {
        Instant start = Instant.parse("2026-07-23T23:00:00Z");
        Instant end = start.plus(Duration.ofMinutes(30));
        PersonEvent breakfast = new PersonEvent(
                EventId.random(),
                ActivityType.EAT,
                "早餐",
                "食堂",
                TimeRange.openEnded(start),
                List.of(),
                "晨起后吃早餐"
        );
        PersonState state = state(0.6, 0.2, 0.3, 0.2, 0.75);

        UPDATER.prepareWithNaturalEvolution(
                PERSON_ID,
                SHANGHAI,
                state,
                List.of(breakfast),
                List.of(breakfast),
                end,
                new StateEvolutionContext(start, Map.of()),
                Map.of()
        );

        assertTrue(state.snapshot().hunger() < 0.75);
    }


    @Test
    void combinedNaturalAndEventEvolutionIsPartitionInvariant() {
        Instant start = Instant.parse("2026-07-23T10:00:00Z");
        Instant end = start.plus(Duration.ofHours(1));
        RegisteredStateEffect drainingEffect = new RegisteredStateEffect(
                EffectId.random(),
                null,
                StateEffectType.EMOTIONAL,
                "sustained cognitive effort",
                start,
                StateEffectEndPolicy.FIXED_TIME,
                end,
                List.of(new StateTransition(StateDimension.ENERGY, -0.8))
        );
        StateEvolutionContext initialContext = new StateEvolutionContext(
                start,
                Map.of(drainingEffect.effectId(), drainingEffect)
        );
        PersonState oneShot = state(0.72, 0.25, 0.35, 0.15, 0.20);
        PersonState partitioned = oneShot.copy();

        UPDATER.prepareWithNaturalEvolution(
                PERSON_ID,
                SHANGHAI,
                oneShot,
                List.of(),
                List.of(),
                end,
                initialContext,
                Map.of()
        );

        StateEvolutionContext context = initialContext;
        for (int step = 1; step <= 4; step++) {
            Instant stepEnd = start.plus(Duration.ofMinutes(15L * step));
            context = UPDATER.prepareWithNaturalEvolution(
                    PERSON_ID,
                    SHANGHAI,
                    partitioned,
                    List.of(),
                    List.of(),
                    stepEnd,
                    context,
                    Map.of()
            ).settledContext();
        }

        PersonStateSnapshot expected = oneShot.snapshot();
        PersonStateSnapshot actual = partitioned.snapshot();
        assertEquals(expected.energy(), actual.energy(), 1.0e-12);
        assertEquals(expected.fatigue(), actual.fatigue(), 1.0e-12);
        assertEquals(expected.mentalLoad(), actual.mentalLoad(), 1.0e-12);
        assertEquals(expected.sleepiness(), actual.sleepiness(), 1.0e-12);
        assertEquals(expected.hunger(), actual.hunger(), 1.0e-12);
    }

    @Test
    void nightTimeAndLongFastingNaturallyIncreaseSleepinessAndHunger() {
        Instant start = Instant.parse("2026-07-23T14:00:00Z"); // 22:00 Shanghai
        Instant end = start.plus(Duration.ofHours(4));
        PersonState state = state(0.6, 0.2, 0.3, 0.10, 0.20);

        settle(state, List.of(), start, end);

        assertTrue(state.snapshot().sleepiness() > 0.10);
        assertTrue(state.snapshot().hunger() > 0.20);
    }

    private static void settle(
            PersonState state,
            List<PersonEvent> events,
            Instant start,
            Instant end
    ) {
        List<PersonEvent> current = events.stream()
                .filter(event -> event.contains(end.minusNanos(1)))
                .toList();
        UPDATER.prepareWithNaturalEvolution(
                PERSON_ID,
                SHANGHAI,
                state,
                current,
                events,
                end,
                new StateEvolutionContext(start, Map.of()),
                Map.of()
        );
    }

    private static PersonEvent event(
            ActivityType type,
            String title,
            Instant start,
            Instant end
    ) {
        return new PersonEvent(
                EventId.random(),
                type,
                title,
                "宿舍",
                TimeRange.closed(start, end),
                List.of(),
                ""
        );
    }

    private static PersonState state(
            double energy,
            double fatigue,
            double mentalLoad,
            double sleepiness,
            double hunger
    ) {
        return new PersonState(
                new AffectState(0.0, energy, 0.0),
                new CognitiveState(0.8, mentalLoad, 0.8),
                new PhysicalState(fatigue, sleepiness, hunger),
                SocialState.baseline()
        );
    }
}
