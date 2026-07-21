package com.laishengkai.digitalperson;

public final class AffectState {

    private double valence;
    private double energy;
    private double tension;

    public AffectState(double valence, double energy, double tension) {
        this.valence = validateValence(valence);
        this.energy = validateUnitValue(energy, "energy");
        this.tension = validateUnitValue(tension, "tension");
    }

    public double getValence() {
        return valence;
    }

    public double getEnergy() {
        return energy;
    }

    public double getTension() {
        return tension;
    }

    private static double validateValence(double value) {
        if (value < -1.0 || value > 1.0) {
            throw new IllegalArgumentException("valence must be between -1.0 and 1.0");
        }
        return value;
    }

    private static double validateUnitValue(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0");
        }
        return value;
    }
}
