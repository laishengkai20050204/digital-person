package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.EventId;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stores the state effect currently assigned to one activity channel.
 */
public record ChannelStateEffect(
        ActivityChannel channel,
        EventId eventId,
        List<StateTransition> transitions
) {

    public ChannelStateEffect {
        Objects.requireNonNull(channel, "channel cannot be null");
        Objects.requireNonNull(eventId, "eventId cannot be null");
        transitions = List.copyOf(
                Objects.requireNonNull(transitions, "transitions cannot be null")
        );

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
}
