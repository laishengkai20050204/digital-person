package com.laishengkai.digitalperson.state;

/** Model-facing direction for one state transition. */
public enum StateEffectDirection {
    INCREASE(1.0),
    DECREASE(-1.0);

    private final double sign;

    StateEffectDirection(double sign) {
        this.sign = sign;
    }

    public double apply(double magnitude) {
        return sign * magnitude;
    }
}
