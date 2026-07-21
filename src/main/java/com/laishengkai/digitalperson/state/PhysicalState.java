package com.laishengkai.digitalperson.state;

/**
 * Short-term physical condition.
 * Every value is represented from {@code 0.0} to {@code 1.0}.
 */
public final class PhysicalState {
    private double fatigue;
    private double sleepiness;
    private double hunger;

    public PhysicalState(double fatigue, double sleepiness, double hunger) {
        setFatigue(fatigue);
        setSleepiness(sleepiness);
        setHunger(hunger);
    }

    public static PhysicalState baseline() {
        return new PhysicalState(0.0, 0.0, 0.0);
    }

    public double getFatigue() {
        return fatigue;
    }

    public double getSleepiness() {
        return sleepiness;
    }

    public double getHunger() {
        return hunger;
    }

    void setFatigue(double fatigue) {
        this.fatigue = validateUnitValue(fatigue, "fatigue");
    }

    void setSleepiness(double sleepiness) {
        this.sleepiness = validateUnitValue(sleepiness, "sleepiness");
    }

    void setHunger(double hunger) {
        this.hunger = validateUnitValue(hunger, "hunger");
    }

    void adjust(double fatigueDelta, double sleepinessDelta, double hungerDelta) {
        fatigue = applyDelta(fatigue, fatigueDelta, "fatigueDelta");
        sleepiness = applyDelta(sleepiness, sleepinessDelta, "sleepinessDelta");
        hunger = applyDelta(hunger, hungerDelta, "hungerDelta");
    }

    PhysicalState copy() {
        return new PhysicalState(fatigue, sleepiness, hunger);
    }

    private static double validateUnitValue(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be a finite value between 0.0 and 1.0"
            );
        }
        return value;
    }

    private static double applyDelta(double current, double delta, String name) {
        if (!Double.isFinite(delta)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return Math.max(0.0, Math.min(1.0, current + delta));
    }

    @Override
    public String toString() {
        return "PhysicalState[fatigue=" + fatigue
                + ", sleepiness=" + sleepiness
                + ", hunger=" + hunger + "]";
    }
}
