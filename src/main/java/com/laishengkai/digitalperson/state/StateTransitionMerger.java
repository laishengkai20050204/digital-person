package com.laishengkai.digitalperson.state;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Merges the signed shapes supplied by all currently active state effects. */
public final class StateTransitionMerger {

    private static final double ZERO_EPSILON = 1.0e-12;

    public List<StateTransition> merge(Collection<? extends StateEffect> effects) {
        Collection<? extends StateEffect> requestedEffects = Objects.requireNonNull(
                effects,
                "effects cannot be null"
        );
        Map<StateDimension, Double> mergedShapes = new EnumMap<>(StateDimension.class);

        for (StateEffect effect : requestedEffects) {
            StateEffect nonNullEffect = Objects.requireNonNull(
                    effect,
                    "effect cannot be null"
            );
            for (StateTransition transition : nonNullEffect.transitions()) {
                mergedShapes.merge(
                        transition.dimension(),
                        transition.shape(),
                        Double::sum
                );
            }
        }

        return mergedShapes.entrySet().stream()
                .filter(entry -> Math.abs(entry.getValue()) > ZERO_EPSILON)
                .map(entry -> {
                    double shape = entry.getValue();
                    if (!Double.isFinite(shape)) {
                        throw new IllegalArgumentException("merged shape must be finite");
                    }
                    double boundedShape = Math.max(
                            -StateTransition.MAX_ABSOLUTE_SHAPE,
                            Math.min(StateTransition.MAX_ABSOLUTE_SHAPE, shape)
                    );
                    return new StateTransition(entry.getKey(), boundedShape);
                })
                .toList();
    }
}
