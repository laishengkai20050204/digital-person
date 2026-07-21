package com.laishengkai.digitalperson.personality;

/**
 * A person's stable personality traits based on the HEXACO model.
 *
 * <p>Every dimension is represented by a value from {@code 0.0} to {@code 1.0}.
 */
public final class Personality {

    private final double honestyHumility;
    private final double emotionality;
    private final double extraversion;
    private final double agreeableness;
    private final double conscientiousness;
    private final double openness;

    public Personality(
            double honestyHumility,
            double emotionality,
            double extraversion,
            double agreeableness,
            double conscientiousness,
            double openness
    ) {
        this.honestyHumility = validate("honestyHumility", honestyHumility);
        this.emotionality = validate("emotionality", emotionality);
        this.extraversion = validate("extraversion", extraversion);
        this.agreeableness = validate("agreeableness", agreeableness);
        this.conscientiousness = validate("conscientiousness", conscientiousness);
        this.openness = validate("openness", openness);
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
