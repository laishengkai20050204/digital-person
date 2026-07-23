package com.laishengkai.digitalperson.state;

import java.util.EnumSet;
import java.util.Set;

/** Semantic category of one independently managed state effect. */
public enum StateEffectType {
    EMOTIONAL(EnumSet.of(
            StateDimension.VALENCE,
            StateDimension.ENERGY,
            StateDimension.TENSION
    )),
    COGNITIVE(EnumSet.of(
            StateDimension.FOCUS,
            StateDimension.MENTAL_LOAD,
            StateDimension.MOTIVATION
    )),
    PHYSICAL(EnumSet.of(
            StateDimension.FATIGUE,
            StateDimension.SLEEPINESS,
            StateDimension.HUNGER
    )),
    SOCIAL(EnumSet.of(
            StateDimension.LONELINESS,
            StateDimension.SOCIAL_NEED
    )),
    /** Compatibility category for legacy evaluators whose transitions span groups. */
    GENERAL(EnumSet.allOf(StateDimension.class));

    private final Set<StateDimension> supportedDimensions;

    StateEffectType(Set<StateDimension> supportedDimensions) {
        this.supportedDimensions = Set.copyOf(supportedDimensions);
    }

    public boolean supports(StateDimension dimension) {
        return supportedDimensions.contains(dimension);
    }

    public Set<StateDimension> supportedDimensions() {
        return supportedDimensions;
    }
}
