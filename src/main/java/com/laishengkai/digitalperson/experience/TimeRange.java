package com.laishengkai.digitalperson.experience;

import lombok.ToString;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A half-open time interval: {@code [start, end)}.
 *
 * <p>An absent end represents an open-ended interval.
 */
@ToString
public final class TimeRange {

    private final Instant start;
    private Instant end;

    private TimeRange(Instant start, Instant end) {
        this.start = Objects.requireNonNull(start, "start cannot be null");
        if (end != null) {
            validateEnd(end);
        }
        this.end = end;
    }

    public static TimeRange closed(Instant start, Instant end) {
        return new TimeRange(start, Objects.requireNonNull(end, "end cannot be null"));
    }

    public static TimeRange openEnded(Instant start) {
        return new TimeRange(start, null);
    }

    public Instant getStart() {
        return start;
    }

    public Optional<Instant> getEnd() {
        return Optional.ofNullable(end);
    }

    public boolean isOpenEnded() {
        return end == null;
    }

    public boolean contains(Instant time) {
        Objects.requireNonNull(time, "time cannot be null");
        return !time.isBefore(start) && (end == null || time.isBefore(end));
    }

    public boolean overlaps(TimeRange other) {
        Objects.requireNonNull(other, "other cannot be null");

        boolean startsBeforeOtherEnds = other.end == null || start.isBefore(other.end);
        boolean otherStartsBeforeThisEnds = end == null || other.start.isBefore(end);
        return startsBeforeOtherEnds && otherStartsBeforeThisEnds;
    }

    public Optional<Duration> duration() {
        return end == null
                ? Optional.empty()
                : Optional.of(Duration.between(start, end));
    }

    public void finish(Instant endTime) {
        Objects.requireNonNull(endTime, "endTime cannot be null");

        if (end != null) {
            throw new IllegalStateException("time range has already finished");
        }

        validateEnd(endTime);
        end = endTime;
    }

    private void validateEnd(Instant endTime) {
        if (!endTime.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }
}
