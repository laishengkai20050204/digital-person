package com.laishengkai.digitalperson.person;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Stable, structured identity owned by one digital-person aggregate. */
public record PersonIdentity(
        String displayName,
        LocalDate birthDate,
        String genderIdentity,
        String residence,
        ZoneId timeZone,
        Locale locale,
        List<String> roles,
        String background
) {
    public PersonIdentity {
        displayName = requireText(displayName, "displayName");
        genderIdentity = normalize(genderIdentity);
        residence = normalize(residence);
        timeZone = Objects.requireNonNull(timeZone, "timeZone cannot be null");
        locale = Objects.requireNonNull(locale, "locale cannot be null");
        roles = normalizeRoles(roles);
        background = normalize(background);
    }

    /** Backward-compatible identity for aggregates created before identity was persisted. */
    public static PersonIdentity unspecified() {
        return new PersonIdentity(
                "未命名人物",
                null,
                "",
                "",
                ZoneOffset.UTC,
                Locale.ROOT,
                List.of(),
                ""
        );
    }

    private static List<String> normalizeRoles(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String role = requireText(value, "role");
            normalized.add(role);
        }
        return List.copyOf(normalized);
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
