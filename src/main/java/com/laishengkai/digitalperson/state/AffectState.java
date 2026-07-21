package com.laishengkai.digitalperson.state;

import lombok.Getter;
import lombok.ToString;

@Getter
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

    /**
     * Applies relative changes while keeping every dimension inside its range.
     */
    public void adjust(
            double valenceDelta,
            double energyDelta,
            double tensionDelta
    ) {
        valence = applyDelta(valence, valenceDelta, -1.0, 1.0, "valenceDelta");
        energy = applyDelta(energy, energyDelta, 0.0, 1.0, "energyDelta");
        tension = applyDelta(tension, tensionDelta, 0.0, 1.0, "tensionDelta");
    }

    private static double validateValence(double value) {
        if (!Double.isFinite(value) || value < -1.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    "valence must be a finite value between -1.0 and 1.0"
            );
        }
        return value;
    }

    private static double validateUnitValue(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be a finite value between 0.0 and 1.0"
            );
        }
        return value;
    }

    private static double applyDelta(
            double current,
            double delta,
            double minimum,
            double maximum,
            String name
    ) {
        if (!Double.isFinite(delta)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return Math.max(minimum, Math.min(maximum, current + delta));
    }
}
