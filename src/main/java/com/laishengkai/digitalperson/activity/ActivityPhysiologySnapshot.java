package com.laishengkai.digitalperson.activity;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.ActivityDurationStatus;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.PersonEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Java-derived physiological timing facts supplied to autonomous activity decisions. */
public record ActivityPhysiologySnapshot(
        String activePrimaryActivityType,
        long activePrimaryElapsedMinutes,
        ActivityDurationStatus activePrimaryDurationStatus,
        long sleepMinutesLast24Hours,
        long sleepDebtMinutes,
        Long awakeMinutes,
        Long minutesSinceLastMeal
) {
    private static final long TARGET_SLEEP_MINUTES = 8L * 60L;

    public ActivityPhysiologySnapshot {
        activePrimaryActivityType = activePrimaryActivityType == null
                ? null
                : activePrimaryActivityType.strip();
        if (activePrimaryElapsedMinutes < 0
                || sleepMinutesLast24Hours < 0
                || sleepDebtMinutes < 0) {
            throw new IllegalArgumentException("physiological durations cannot be negative");
        }
        if (activePrimaryActivityType == null) {
            if (activePrimaryElapsedMinutes != 0 || activePrimaryDurationStatus != null) {
                throw new IllegalArgumentException(
                        "missing active primary activity cannot have duration metadata"
                );
            }
        } else {
            activePrimaryDurationStatus = Objects.requireNonNull(
                    activePrimaryDurationStatus,
                    "activePrimaryDurationStatus cannot be null"
            );
        }
        requireNullableNonNegative(awakeMinutes, "awakeMinutes");
        requireNullableNonNegative(minutesSinceLastMeal, "minutesSinceLastMeal");
    }

    public static ActivityPhysiologySnapshot from(
            List<PersonEvent> personEvents,
            Instant evaluationTime
    ) {
        List<PersonEvent> events = List.copyOf(Objects.requireNonNull(
                personEvents,
                "personEvents cannot be null"
        ));
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );

        PersonEvent activePrimary = events.stream()
                .filter(event -> event.getChannel() == ActivityChannel.PRIMARY)
                .filter(event -> event.contains(now))
                .max(Comparator.comparing(PersonEvent::getStartTime))
                .orElse(null);
        long activeMinutes = activePrimary == null
                ? 0L
                : nonNegativeMinutes(activePrimary.getStartTime(), now);

        Instant windowStart = now.minus(Duration.ofHours(24));
        long sleepMinutes = events.stream()
                .filter(event -> event.getActivityType() == ActivityType.SLEEP)
                .mapToLong(event -> overlapMinutes(event, windowStart, now))
                .sum();
        long sleepDebtMinutes = Math.max(0L, TARGET_SLEEP_MINUTES - sleepMinutes);

        Long awakeMinutes = calculateAwakeMinutes(events, activePrimary, now);
        Long minutesSinceLastMeal = calculateMinutesSinceLastMeal(events, activePrimary, now);

        return new ActivityPhysiologySnapshot(
                activePrimary == null ? null : activePrimary.getActivityType().name(),
                activeMinutes,
                activePrimary == null
                        ? null
                        : ActivityDurationStatus.classify(
                                activePrimary.getActivityType(),
                                activeMinutes
                        ),
                sleepMinutes,
                sleepDebtMinutes,
                awakeMinutes,
                minutesSinceLastMeal
        );
    }

    public static ActivityPhysiologySnapshot empty() {
        return new ActivityPhysiologySnapshot(
                null,
                0L,
                null,
                0L,
                TARGET_SLEEP_MINUTES,
                null,
                null
        );
    }

    private static Long calculateAwakeMinutes(
            List<PersonEvent> events,
            PersonEvent activePrimary,
            Instant now
    ) {
        if (activePrimary != null && activePrimary.getActivityType() == ActivityType.SLEEP) {
            return 0L;
        }
        return events.stream()
                .filter(event -> event.getActivityType() == ActivityType.SLEEP)
                .map(PersonEvent::getEndTime)
                .flatMap(java.util.Optional::stream)
                .filter(end -> !end.isAfter(now))
                .max(Comparator.naturalOrder())
                .map(end -> nonNegativeMinutes(end, now))
                .orElse(null);
    }

    private static Long calculateMinutesSinceLastMeal(
            List<PersonEvent> events,
            PersonEvent activePrimary,
            Instant now
    ) {
        if (activePrimary != null && activePrimary.getActivityType() == ActivityType.EAT) {
            return 0L;
        }
        return events.stream()
                .filter(event -> event.getActivityType() == ActivityType.EAT)
                .map(PersonEvent::getEndTime)
                .flatMap(java.util.Optional::stream)
                .filter(end -> !end.isAfter(now))
                .max(Comparator.naturalOrder())
                .map(end -> nonNegativeMinutes(end, now))
                .orElse(null);
    }

    private static long overlapMinutes(
            PersonEvent event,
            Instant windowStart,
            Instant windowEnd
    ) {
        Instant start = event.getStartTime().isAfter(windowStart)
                ? event.getStartTime()
                : windowStart;
        Instant rawEnd = event.getEndTime().orElse(windowEnd);
        Instant end = rawEnd.isBefore(windowEnd) ? rawEnd : windowEnd;
        return end.isAfter(start) ? Duration.between(start, end).toMinutes() : 0L;
    }

    private static long nonNegativeMinutes(Instant start, Instant end) {
        return end.isBefore(start) ? 0L : Duration.between(start, end).toMinutes();
    }

    private static void requireNullableNonNegative(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }
}
