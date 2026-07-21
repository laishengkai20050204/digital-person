package com.laishengkai.digitalperson.experience;

import lombok.Getter;
import lombok.ToString;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Getter
@ToString
public final class TimeRange {

    private final LocalDateTime start;
    private LocalDateTime end;

    public TimeRange(LocalDateTime start, LocalDateTime end) {
        this.start = Objects.requireNonNull(start, "start cannot be null");
        this.end = Objects.requireNonNull(end, "end cannot be null");
        validateEnd(end);
    }

    private TimeRange(LocalDateTime start) {
        this.start = Objects.requireNonNull(start, "start cannot be null");
    }

    public static TimeRange closed(LocalDateTime start, LocalDateTime end) {
        return new TimeRange(start, end);
    }

    public static TimeRange openEnded(LocalDateTime start) {
        return new TimeRange(start);
    }

    public Optional<LocalDateTime> getEnd() {
        return Optional.ofNullable(end);
    }

    public boolean isOpenEnded() {
        return end == null;
    }

    public boolean contains(LocalDateTime time) {
        Objects.requireNonNull(time, "time cannot be null");
        return !time.isBefore(start) && (end == null || time.isBefore(end));
    }

    public EventStatus getStatusAt(LocalDateTime time) {
        Objects.requireNonNull(time, "time cannot be null");

        if (time.isBefore(start)) {
            return EventStatus.PLANNED;
        }
        if (end == null || time.isBefore(end)) {
            return EventStatus.IN_PROGRESS;
        }
        return EventStatus.COMPLETED;
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

    public void finish(LocalDateTime endTime) {
        Objects.requireNonNull(endTime, "endTime cannot be null");

        if (end != null) {
            throw new IllegalStateException("time range has already finished");
        }

        validateEnd(endTime);
        end = endTime;
    }

    private void validateEnd(LocalDateTime endTime) {
        if (!endTime.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }
}
