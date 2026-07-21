package com.laishengkai.digitalperson.state;

import java.util.Objects;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

/** Every short-term state value that can be changed by the transition model. */
public enum StateDimension {
    VALENCE(-1.0, 1.0,
            state -> state.getAffectState().getValence(),
            (state, value) -> state.getAffectState().setValence(value)),
    ENERGY(0.0, 1.0,
            state -> state.getAffectState().getEnergy(),
            (state, value) -> state.getAffectState().setEnergy(value)),
    TENSION(0.0, 1.0,
            state -> state.getAffectState().getTension(),
            (state, value) -> state.getAffectState().setTension(value)),
    FOCUS(0.0, 1.0,
            state -> state.getCognitiveState().getFocus(),
            (state, value) -> state.getCognitiveState().setFocus(value)),
    MENTAL_LOAD(0.0, 1.0,
            state -> state.getCognitiveState().getMentalLoad(),
            (state, value) -> state.getCognitiveState().setMentalLoad(value)),
    MOTIVATION(0.0, 1.0,
            state -> state.getCognitiveState().getMotivation(),
            (state, value) -> state.getCognitiveState().setMotivation(value)),
    FATIGUE(0.0, 1.0,
            state -> state.getPhysicalState().getFatigue(),
            (state, value) -> state.getPhysicalState().setFatigue(value)),
    SLEEPINESS(0.0, 1.0,
            state -> state.getPhysicalState().getSleepiness(),
            (state, value) -> state.getPhysicalState().setSleepiness(value)),
    HUNGER(0.0, 1.0,
            state -> state.getPhysicalState().getHunger(),
            (state, value) -> state.getPhysicalState().setHunger(value)),
    LONELINESS(0.0, 1.0,
            state -> state.getSocialState().getLoneliness(),
            (state, value) -> state.getSocialState().setLoneliness(value)),
    SOCIAL_NEED(0.0, 1.0,
            state -> state.getSocialState().getSocialNeed(),
            (state, value) -> state.getSocialState().setSocialNeed(value));

    private final double minimum;
    private final double maximum;
    private final ToDoubleFunction<PersonState> reader;
    private final ObjDoubleConsumer<PersonState> writer;

    StateDimension(
            double minimum,
            double maximum,
            ToDoubleFunction<PersonState> reader,
            ObjDoubleConsumer<PersonState> writer
    ) {
        this.minimum = minimum;
        this.maximum = maximum;
        this.reader = reader;
        this.writer = writer;
    }

    public double getMinimum() {
        return minimum;
    }

    public double getMaximum() {
        return maximum;
    }

    public double read(PersonState state) {
        return reader.applyAsDouble(Objects.requireNonNull(state, "state cannot be null"));
    }

    void write(PersonState state, double value) {
        writer.accept(Objects.requireNonNull(state, "state cannot be null"), clamp(value));
    }

    public boolean contains(double value) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    public double clamp(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("state value must be finite");
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
