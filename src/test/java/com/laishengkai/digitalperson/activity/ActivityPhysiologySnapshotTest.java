package com.laishengkai.digitalperson.activity;

import com.laishengkai.digitalperson.experience.ActivityDurationStatus;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityPhysiologySnapshotTest {

    @Test
    void derivesSleepDebtAwakeTimeMealTimeAndCurrentDuration() {
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        PersonEvent sleep = closed(
                ActivityType.SLEEP,
                now.minus(Duration.ofHours(9)),
                now.minus(Duration.ofHours(5))
        );
        PersonEvent meal = closed(
                ActivityType.EAT,
                now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(1))
        );
        PersonEvent study = new PersonEvent(
                EventId.random(),
                ActivityType.STUDY,
                "上午学习",
                "宿舍",
                TimeRange.openEnded(now.minus(Duration.ofHours(3))),
                List.of(),
                ""
        );

        ActivityPhysiologySnapshot snapshot = ActivityPhysiologySnapshot.from(
                List.of(sleep, meal, study),
                now
        );

        assertEquals("STUDY", snapshot.activePrimaryActivityType());
        assertEquals(180L, snapshot.activePrimaryElapsedMinutes());
        assertEquals(ActivityDurationStatus.EXTENDED, snapshot.activePrimaryDurationStatus());
        assertEquals(240L, snapshot.sleepMinutesLast24Hours());
        assertEquals(240L, snapshot.sleepDebtMinutes());
        assertEquals(300L, snapshot.awakeMinutes());
        assertEquals(60L, snapshot.minutesSinceLastMeal());
    }

    private static PersonEvent closed(
            ActivityType type,
            Instant start,
            Instant end
    ) {
        return new PersonEvent(
                EventId.random(),
                type,
                type.name(),
                "",
                TimeRange.closed(start, end),
                List.of(),
                ""
        );
    }
}
