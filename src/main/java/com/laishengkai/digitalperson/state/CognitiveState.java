package com.laishengkai.digitalperson.state;

import lombok.Getter;
import lombok.ToString;

/**
 * Short-term cognitive condition.
 * Every value is represented from {@code 0.0} to {@code 1.0}.
 */
@Getter
@ToString
public final class CognitiveState {

    private double focus;
    private double mentalLoad;
    private double motivation;

    public CognitiveState(
            double focus,
            double mentalLoad,
            double motivation
    ) {
        setFocus(focus);
        setMentalLoad(mentalLoad);
        setMotivation(motivation);
    }

    public static CognitiveState baseline() {
        return new CognitiveState(0.5, 0.0, 0.5);
    }

    public void setFocus(double focus) {
        this.focus = validateUnitValue(focus, "focus");
    }

    public void setMentalLoad(double mentalLoad) {
        this.mentalLoad = validateUnitValue(mentalLoad, "mentalLoad");
    }

    public void setMotivation(double motivation) {
        this.motivation = validateUnitValue(motivation, "motivation");
    }

    private static double validateUnitValue(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be a finite value between 0.0 and 1.0"
            );
        }
        return value;
    }
}
