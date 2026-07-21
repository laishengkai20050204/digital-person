package com.laishengkai.digitalperson;

public final class AffectState {

    private double valence;
    private double energy;
    private double tension;

    public AffectState(double valence, double energy, double tension) {
        this.valence = validate(valence, "valence");
        this.energy = validate(energy, "energy");
        this.tension = validate(tension, "tension");
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

    private static double validate(double value, String name) {
        if (value < -1.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between -1.0 and 1.0");
        }
        return value;
    }
}
