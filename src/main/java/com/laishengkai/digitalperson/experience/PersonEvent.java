package com.laishengkai.digitalperson.experience;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@ToString
@EqualsAndHashCode
public final class PersonEvent {

    private final String description;
    private final String location;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;

    public PersonEvent(
            String description,
            String location,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        this.description = requireText(description, "description");
        this.location = normalize(location);
        this.startTime = Objects.requireNonNull(startTime, "startTime cannot be null");
        this.endTime = Objects.requireNonNull(endTime, "endTime cannot be null");

        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }
    }

    public PersonEvent(
            String description,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        this(description, "", startTime, endTime);
    }

    public boolean contains(LocalDateTime time) {
        Objects.requireNonNull(time, "time cannot be null");
        return !time.isBefore(startTime) && time.isBefore(endTime);
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

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
