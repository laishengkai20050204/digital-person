package com.laishengkai.digitalperson.state;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public final class AffectState {

    private double valence;
    private double energy;
    private double tension;

    public AffectState(double valence, double energy, double tension) {
        setValence(valence);
        setEnergy(energy);
        setTension(tension);
    }

    public void setValence(double valence) {
        this.valence = validateValence(valence);
    }

    public void setEnergy(double energy) {
        this.energy = validateUnitValue(energy, "energy");
    }

    public void setTension(double tension) {
        this.tension = validateUnitValue(tension, "tension");
    }

    private static double validateValence(double value) {
        if (!Double.isFinite(value) || value < -1.0 || value > 1.0) {
            throw new IllegalArgumentException("valence must be a finite value between -1.0 and 1.0");
        }
        return value;
    }

    private static double validateUnitValue(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be a finite value between 0.0 and 1.0");
        }
        return value;
    }
}
