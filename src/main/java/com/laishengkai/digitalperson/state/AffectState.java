package com.laishengkai.digitalperson.state;

/** Short-term emotional condition. */
public final class AffectState {
    private double valence;
    private double energy;
    private double tension;

    public AffectState(double valence, double energy, double tension) {
        setValence(valence);
        setEnergy(energy);
        setTension(tension);
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

    void setValence(double valence) {
        this.valence = validateValence(valence);
    }

    void setEnergy(double energy) {
        this.energy = validateUnitValue(energy, "energy");
    }

    void setTension(double tension) {
        this.tension = validateUnitValue(tension, "tension");
    }

    void adjust(double valenceDelta, double energyDelta, double tensionDelta) {
        valence = applyDelta(valence, valenceDelta, -1.0, 1.0, "valenceDelta");
        energy = applyDelta(energy, energyDelta, 0.0, 1.0, "energyDelta");
        tension = applyDelta(tension, tensionDelta, 0.0, 1.0, "tensionDelta");
    }

    AffectState copy() {
        return new AffectState(valence, energy, tension);
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

    @Override
    public String toString() {
        return "AffectState[valence=" + valence
                + ", energy=" + energy
                + ", tension=" + tension + "]";
    }
}
