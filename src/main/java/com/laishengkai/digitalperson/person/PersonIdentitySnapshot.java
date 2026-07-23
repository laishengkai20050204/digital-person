package com.laishengkai.digitalperson.person;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Objects;

/** Immutable identity representation supplied to prompts and read models. */
public record PersonIdentitySnapshot(
        String displayName,
        LocalDate birthDate,
        Integer age,
        String genderIdentity,
        String residence,
        String timeZone,
        String locale,
        List<String> roles,
        String background
) {
    public PersonIdentitySnapshot {
        displayName = requireText(displayName, "displayName");
        if (age != null && age < 0) {
            throw new IllegalArgumentException("age cannot be negative");
        }
        genderIdentity = normalize(genderIdentity);
        residence = normalize(residence);
        timeZone = requireText(timeZone, "timeZone");
        locale = requireText(locale, "locale");
        roles = List.copyOf(Objects.requireNonNullElse(roles, List.of()));
        if (roles.stream().anyMatch(role -> role == null || role.isBlank())) {
            throw new IllegalArgumentException("roles cannot contain blank values");
        }
        background = normalize(background);
    }

    public static PersonIdentitySnapshot from(
            PersonIdentity identity,
            Instant evaluationTime
    ) {
        PersonIdentity source = Objects.requireNonNull(identity, "identity cannot be null");
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        LocalDate evaluationDate = now.atZone(source.timeZone()).toLocalDate();
        Integer age = null;
        if (source.birthDate() != null) {
            if (source.birthDate().isAfter(evaluationDate)) {
                throw new IllegalArgumentException(
                        "birthDate cannot be after evaluation date"
                );
            }
            age = Period.between(source.birthDate(), evaluationDate).getYears();
        }
        return new PersonIdentitySnapshot(
                source.displayName(),
                source.birthDate(),
                age,
                source.genderIdentity(),
                source.residence(),
                source.timeZone().getId(),
                source.locale().toLanguageTag(),
                source.roles(),
                source.background()
        );
    }

    public static PersonIdentitySnapshot unspecified() {
        return from(PersonIdentity.unspecified(), Instant.EPOCH);
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
