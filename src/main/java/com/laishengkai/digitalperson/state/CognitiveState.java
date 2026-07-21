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

    /**
     * Applies relative changes while keeping every dimension inside [0, 1].
     */
    public void adjust(
            double focusDelta,
            double mentalLoadDelta,
            double motivationDelta
    ) {
        focus = applyDelta(focus, focusDelta, "focusDelta");
        mentalLoad = applyDelta(mentalLoad, mentalLoadDelta, "mentalLoadDelta");
        motivation = applyDelta(motivation, motivationDelta, "motivationDelta");
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
}
