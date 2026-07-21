package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.PersonEvent;

import java.time.Duration;
import java.time.Instant;
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
 *
 * <p>The updater records its own last settlement time. Every call settles state
 * only through the supplied {@code now}; it never projects state to an event's
 * future end time.</p>
 */
public final class StateUpdater {

    private final StateTransitionEvaluator evaluator;
    private final StateTransitionModel transitionModel;
    private final StateTransitionMerger transitionMerger;
    private final Map<ActivityChannel, ChannelStateEffect> channelEffects =
            new EnumMap<>(ActivityChannel.class);

    private Instant lastUpdatedAt;

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
     * Settles cached channel effects from the previous update time through
     * {@code now}, then detects channel event changes. Newly evaluated shapes
     * begin at {@code now} and are reused by later calls.
     */
    public synchronized void update(
            PersonState state,
            List<PersonEvent> currentEvents,
            Instant now
    ) {
        PersonState currentState = Objects.requireNonNull(
                state,
                "state cannot be null"
        );
        Instant currentTime = Objects.requireNonNull(
                now,
                "now cannot be null"
        );
        Map<ActivityChannel, PersonEvent> eventsByChannel = indexByChannel(
                currentEvents
        );

        settleUntil(currentState, currentTime);

        // The old cached effects have now been fully settled through currentTime.
        // Record the time before LLM evaluation so a failed evaluation cannot make
        // a retry apply the same elapsed interval twice.
        lastUpdatedAt = currentTime;

        refreshChangedChannels(currentState, eventsByChannel);
    }

    private void settleUntil(PersonState state, Instant now) {
        if (lastUpdatedAt == null) {
            return;
        }
        if (now.isBefore(lastUpdatedAt)) {
            throw new IllegalArgumentException(
                    "now cannot be before the previous update time"
            );
        }

        Duration elapsed = Duration.between(lastUpdatedAt, now);
        if (elapsed.isZero()) {
            return;
        }

        List<StateTransition> mergedTransitions = transitionMerger.merge(
                channelEffects.values()
        );
        transitionModel.applyAll(
                state,
                mergedTransitions,
                elapsed
        );
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
