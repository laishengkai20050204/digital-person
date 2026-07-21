package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.PersonEvent;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Coordinates event-driven state evaluation and deterministic state updates.
 *
 * <p>Each channel keeps the effect of its current event. The evaluator is called
 * only when a different event becomes current in that channel. Effects from all
 * channels are merged before the person state is modified.</p>
 */
public final class StateUpdater {

    private final StateTransitionEvaluator evaluator;
    private final StateTransitionModel transitionModel;
    private final StateTransitionMerger transitionMerger;
    private final Map<ActivityChannel, ChannelStateEffect> channelEffects =
            new EnumMap<>(ActivityChannel.class);

    public StateUpdater(StateTransitionEvaluator evaluator) {
        this(
                evaluator,
                new StateTransitionModel(),
                new StateTransitionMerger()
        );
    }

    public StateUpdater(
            StateTransitionEvaluator evaluator,
            StateTransitionModel transitionModel,
            StateTransitionMerger transitionMerger
    ) {
        this.evaluator = Objects.requireNonNull(
                evaluator,
                "evaluator cannot be null"
        );
        this.transitionModel = Objects.requireNonNull(
                transitionModel,
                "transitionModel cannot be null"
        );
        this.transitionMerger = Objects.requireNonNull(
                transitionMerger,
                "transitionMerger cannot be null"
        );
    }

    /**
     * First settles the previously cached channel effects for {@code elapsed},
     * then detects channel event changes. A newly evaluated shape starts taking
     * effect after this call and is reused by later calls.
     */
    public synchronized void update(
            PersonState state,
            List<PersonEvent> currentEvents,
            Duration elapsed
    ) {
        PersonState currentState = Objects.requireNonNull(
                state,
                "state cannot be null"
        );
        Duration elapsedTime = Objects.requireNonNull(
                elapsed,
                "elapsed cannot be null"
        );
        if (elapsedTime.isNegative()) {
            throw new IllegalArgumentException("elapsed cannot be negative");
        }

        Map<ActivityChannel, PersonEvent> eventsByChannel = indexByChannel(
                currentEvents
        );

        if (!elapsedTime.isZero()) {
            List<StateTransition> mergedTransitions = transitionMerger.merge(
                    channelEffects.values()
            );
            transitionModel.applyAll(
                    currentState,
                    mergedTransitions,
                    elapsedTime
            );
        }

        refreshChangedChannels(currentState, eventsByChannel);
    }

    private void refreshChangedChannels(
            PersonState state,
            Map<ActivityChannel, PersonEvent> currentEvents
    ) {
        Map<ActivityChannel, ChannelStateEffect> refreshedEffects =
                new EnumMap<>(channelEffects);

        for (ActivityChannel channel : ActivityChannel.values()) {
            PersonEvent currentEvent = currentEvents.get(channel);
            ChannelStateEffect existingEffect = channelEffects.get(channel);

            if (currentEvent == null) {
                refreshedEffects.remove(channel);
                continue;
            }
            if (existingEffect != null
                    && existingEffect.eventId().equals(currentEvent.getId())) {
                continue;
            }

            List<StateTransition> transitions = List.copyOf(
                    Objects.requireNonNull(
                            evaluator.evaluate(state, currentEvent),
                            "evaluator result cannot be null"
                    )
            );
            refreshedEffects.put(
                    channel,
                    new ChannelStateEffect(
                            channel,
                            currentEvent.getId(),
                            transitions
                    )
            );
        }

        channelEffects.clear();
        channelEffects.putAll(refreshedEffects);
    }

    private static Map<ActivityChannel, PersonEvent> indexByChannel(
            List<PersonEvent> currentEvents
    ) {
        List<PersonEvent> events = List.copyOf(
                Objects.requireNonNull(
                        currentEvents,
                        "currentEvents cannot be null"
                )
        );
        Map<ActivityChannel, PersonEvent> eventsByChannel =
                new EnumMap<>(ActivityChannel.class);

        for (PersonEvent event : events) {
            PersonEvent previous = eventsByChannel.put(
                    event.getChannel(),
                    event
            );
            if (previous != null) {
                throw new IllegalArgumentException(
                        "only one current event is allowed per activity channel"
                );
            }
        }

        return eventsByChannel;
    }
}
