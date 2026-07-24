package com.laishengkai.digitalperson.experience;

import java.util.Objects;

/** Java-derived realism classification for an event's elapsed duration. */
public enum ActivityDurationStatus {
    NORMAL,
    EXTENDED,
    SEVERELY_EXTENDED,
    STALE;

    public static ActivityDurationStatus classify(ActivityType type, long elapsedMinutes) {
        ActivityType activityType = Objects.requireNonNull(type, "type cannot be null");
        if (elapsedMinutes < 0) {
            throw new IllegalArgumentException("elapsedMinutes cannot be negative");
        }
        long[] thresholds = thresholds(activityType);
        if (elapsedMinutes < thresholds[0]) {
            return NORMAL;
        }
        if (elapsedMinutes < thresholds[1]) {
            return EXTENDED;
        }
        if (elapsedMinutes < thresholds[2]) {
            return SEVERELY_EXTENDED;
        }
        return STALE;
    }

    private static long[] thresholds(ActivityType type) {
        return switch (type) {
            case STUDY, WORK -> new long[]{120, 240, 360};
            case EAT -> new long[]{60, 120, 180};
            case SLEEP -> new long[]{540, 660, 840};
            case EXERCISE -> new long[]{90, 180, 300};
            case REST -> new long[]{120, 240, 480};
            case CHAT -> new long[]{120, 360, 720};
            case LISTEN_MUSIC -> new long[]{240, 480, 720};
            default -> new long[]{180, 360, 720};
        };
    }
}
