package com.laishengkai.digitalperson.modelcontext;

import com.laishengkai.digitalperson.person.PersonIdentity;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/** Model-friendly current-time view in both UTC and the person's local timezone. */
public record TemporalContextSnapshot(
        Instant now,
        String timeZone,
        OffsetDateTime localDateTime,
        DayOfWeek dayOfWeek,
        int hourOfDay,
        boolean weekend
) {
    public TemporalContextSnapshot {
        now = Objects.requireNonNull(now, "now cannot be null");
        timeZone = requireText(timeZone, "timeZone");
        localDateTime = Objects.requireNonNull(
                localDateTime,
                "localDateTime cannot be null"
        );
        dayOfWeek = Objects.requireNonNull(dayOfWeek, "dayOfWeek cannot be null");
        if (hourOfDay < 0 || hourOfDay > 23) {
            throw new IllegalArgumentException("hourOfDay must be between 0 and 23");
        }
        if (localDateTime.getHour() != hourOfDay) {
            throw new IllegalArgumentException(
                    "hourOfDay must match localDateTime"
            );
        }
        if (localDateTime.getDayOfWeek() != dayOfWeek) {
            throw new IllegalArgumentException(
                    "dayOfWeek must match localDateTime"
            );
        }
        boolean expectedWeekend = dayOfWeek == DayOfWeek.SATURDAY
                || dayOfWeek == DayOfWeek.SUNDAY;
        if (weekend != expectedWeekend) {
            throw new IllegalArgumentException(
                    "weekend must match dayOfWeek"
            );
        }
    }

    public static TemporalContextSnapshot from(
            PersonIdentity identity,
            Instant evaluationTime
    ) {
        PersonIdentity source = Objects.requireNonNull(identity, "identity cannot be null");
        return from(source.timeZone(), evaluationTime);
    }

    public static TemporalContextSnapshot from(
            ZoneId timeZone,
            Instant evaluationTime
    ) {
        ZoneId zone = Objects.requireNonNull(timeZone, "timeZone cannot be null");
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        ZonedDateTime local = now.atZone(zone);
        DayOfWeek day = local.getDayOfWeek();
        return new TemporalContextSnapshot(
                now,
                zone.getId(),
                local.toOffsetDateTime(),
                day,
                local.getHour(),
                day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
        );
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }
}
