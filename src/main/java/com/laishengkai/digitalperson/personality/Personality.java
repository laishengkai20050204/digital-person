package com.laishengkai.digitalperson.personality;

/**
 * A person's stable personality traits based on the HEXACO model.
 *
 * <p>Every dimension is represented by a finite value from {@code 0.0} to
 * {@code 1.0}. The legacy JavaBean getters remain available so persistence,
 * prompts and external adapters do not need to change access style.</p>
 */
public record Personality(
        double honestyHumility,
        double emotionality,
        double extraversion,
        double agreeableness,
        double conscientiousness,
        double openness
) {
    public Personality {
        honestyHumility = validate("honestyHumility", honestyHumility);
        emotionality = validate("emotionality", emotionality);
        extraversion = validate("extraversion", extraversion);
        agreeableness = validate("agreeableness", agreeableness);
        conscientiousness = validate("conscientiousness", conscientiousness);
        openness = validate("openness", openness);
    }

    public double getHonestyHumility() {
        return honestyHumility;
    }

    public double getEmotionality() {
        return emotionality;
    }

    public double getExtraversion() {
        return extraversion;
    }

    public double getAgreeableness() {
        return agreeableness;
    }

    public double getConscientiousness() {
        return conscientiousness;
    }

    public double getOpenness() {
        return openness;
    }

    private static double validate(String dimensionName, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    dimensionName + " must be a finite value between 0.0 and 1.0"
            );
        }
        return value;
    }
}
