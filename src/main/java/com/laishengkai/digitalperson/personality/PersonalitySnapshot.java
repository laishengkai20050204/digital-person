package com.laishengkai.digitalperson.personality;

import java.util.Objects;

/** Immutable HEXACO personality view for prompts and APIs. */
public record PersonalitySnapshot(
        double honestyHumility,
        double emotionality,
        double extraversion,
        double agreeableness,
        double conscientiousness,
        double openness
) {
    public PersonalitySnapshot {
        validate("honestyHumility", honestyHumility);
        validate("emotionality", emotionality);
        validate("extraversion", extraversion);
        validate("agreeableness", agreeableness);
        validate("conscientiousness", conscientiousness);
        validate("openness", openness);
    }

    public static PersonalitySnapshot from(Personality personality) {
        Personality source = Objects.requireNonNull(
                personality,
                "personality cannot be null"
        );
        return new PersonalitySnapshot(
                source.getHonestyHumility(),
                source.getEmotionality(),
                source.getExtraversion(),
                source.getAgreeableness(),
                source.getConscientiousness(),
                source.getOpenness()
        );
    }

    private static void validate(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be a finite value between 0.0 and 1.0"
            );
        }
    }
}
