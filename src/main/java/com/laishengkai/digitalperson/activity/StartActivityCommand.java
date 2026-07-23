package com.laishengkai.digitalperson.activity;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.ActivityType;

import java.util.List;
import java.util.Objects;

/** Starts one digital-person activity at the application service's command time. */
public record StartActivityCommand(
        ActivityType activityType,
        String title,
        String location,
        List<String> participants,
        String notes
) implements ActivityLifecycleCommand {
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_LOCATION_LENGTH = 200;
    public static final int MAX_PARTICIPANTS = 20;
    public static final int MAX_PARTICIPANT_LENGTH = 100;
    public static final int MAX_NOTES_LENGTH = 1_000;

    public StartActivityCommand {
        activityType = Objects.requireNonNull(
                activityType,
                "activityType cannot be null"
        );
        title = requireText(title, "title", MAX_TITLE_LENGTH);
        location = normalize(location, "location", MAX_LOCATION_LENGTH);
        participants = normalizeParticipants(participants);
        notes = normalize(notes, "notes", MAX_NOTES_LENGTH);
    }

    @Override
    public ActivityLifecycleAction action() {
        return ActivityLifecycleAction.START;
    }

    public ActivityChannel channel() {
        return activityType.getChannel();
    }

    private static List<String> normalizeParticipants(List<String> values) {
        List<String> source = Objects.requireNonNullElse(values, List.of());
        if (source.size() > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException(
                    "participants cannot contain more than " + MAX_PARTICIPANTS + " values"
            );
        }
        return source.stream()
                .map(value -> requireText(
                        value,
                        "participant",
                        MAX_PARTICIPANT_LENGTH
                ))
                .distinct()
                .toList();
    }

    private static String requireText(String value, String name, int maxLength) {
        String normalized = Objects.requireNonNull(
                value,
                name + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " cannot exceed " + maxLength + " characters"
            );
        }
        return normalized;
    }

    private static String normalize(String value, String name, int maxLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " cannot exceed " + maxLength + " characters"
            );
        }
        return normalized;
    }
}
