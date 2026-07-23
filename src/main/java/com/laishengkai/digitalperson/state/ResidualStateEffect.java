package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.EventId;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A time-bounded state effect left by a finished event.
 *
 * <p>Residual effects are keyed by their source event rather than an activity
 * channel, so multiple emotions or other aftermaths can coexist without
 * blocking communication, music, study, sleep or any later activity.</p>
 */
public record ResidualStateEffect(
        EventId sourceEventId,
        Instant startsAt,
        Instant endsAt,
        List<StateTransition> transitions
) implements StateEffect {
    public ResidualStateEffect {
        sourceEventId = Objects.requireNonNull(
                sourceEventId,
                "sourceEventId cannot be null"
        );
        startsAt = Objects.requireNonNull(startsAt, "startsAt cannot be null");
        endsAt = Objects.requireNonNull(endsAt, "endsAt cannot be null");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        transitions = copyTransitions(transitions);
        if (transitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "a residual state effect must contain at least one transition"
            );
        }
    }

    public static ResidualStateEffect fromPlan(
            EventId sourceEventId,
            Instant startsAt,
            AftermathStateEffectPlan plan
    ) {
        AftermathStateEffectPlan requestedPlan = Objects.requireNonNull(
                plan,
                "plan cannot be null"
        );
        if (!requestedPlan.isPresent()) {
            throw new IllegalArgumentException("cannot materialize an empty aftermath plan");
        }
        Instant start = Objects.requireNonNull(startsAt, "startsAt cannot be null");
        return new ResidualStateEffect(
                sourceEventId,
                start,
                start.plus(requestedPlan.duration()),
                requestedPlan.transitions()
        );
    }

    public boolean isActiveAt(Instant time) {
        Instant requestedTime = Objects.requireNonNull(time, "time cannot be null");
        return !requestedTime.isBefore(startsAt) && requestedTime.isBefore(endsAt);
    }

    private static List<StateTransition> copyTransitions(
            List<StateTransition> requestedTransitions
    ) {
        List<StateTransition> copied = List.copyOf(Objects.requireNonNull(
                requestedTransitions,
                "transitions cannot be null"
        ));
        Set<StateDimension> dimensions = EnumSet.noneOf(StateDimension.class);
        for (StateTransition transition : copied) {
            StateTransition nonNullTransition = Objects.requireNonNull(
                    transition,
                    "transition cannot be null"
            );
            if (!dimensions.add(nonNullTransition.dimension())) {
                throw new IllegalArgumentException(
                        "only one residual transition is allowed per state dimension"
                );
            }
        }
        return copied;
    }
}
