package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.EventId;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stores the state effect assigned to one currently active activity channel.
 *
 * <p>The optional aftermath plan is not applied while the activity is open. It
 * is materialized as an independent {@link ResidualStateEffect} when the source
 * event finishes or is replaced.</p>
 */
public record ChannelStateEffect(
        ActivityChannel channel,
        EventId eventId,
        List<StateTransition> transitions,
        AftermathStateEffectPlan aftermath
) implements StateEffect {

    public ChannelStateEffect(
            ActivityChannel channel,
            EventId eventId,
            List<StateTransition> transitions
    ) {
        this(channel, eventId, transitions, AftermathStateEffectPlan.none());
    }

    public ChannelStateEffect {
        Objects.requireNonNull(channel, "channel cannot be null");
        Objects.requireNonNull(eventId, "eventId cannot be null");
        transitions = List.copyOf(
                Objects.requireNonNull(transitions, "transitions cannot be null")
        );
        aftermath = Objects.requireNonNull(aftermath, "aftermath cannot be null");

        Set<StateDimension> seenDimensions = EnumSet.noneOf(StateDimension.class);
        for (StateTransition transition : transitions) {
            StateTransition nonNullTransition = Objects.requireNonNull(
                    transition,
                    "transition cannot be null"
            );
            if (!seenDimensions.add(nonNullTransition.dimension())) {
                throw new IllegalArgumentException(
                        "only one transition is allowed per state dimension in one channel"
                );
            }
        }
    }

    public boolean hasAftermath() {
        return aftermath.isPresent();
    }
}
