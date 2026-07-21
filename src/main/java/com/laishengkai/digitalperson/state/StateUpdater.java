package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless deterministic state evolution service.
 *
 * <p>Use {@link #prepare(PersonState, List, Instant, StateEvolutionContext)}
 * before asynchronous event evaluation, then use
 * {@link #complete(StateUpdatePreparation, Collection)} to commit evaluated
 * channel effects.</p>
 */
public final class StateUpdater {
    private final StateTransitionModel transitionModel;
    private final StateTransitionMerger transitionMerger;

    public StateUpdater() {
        this(new StateTransitionModel(), new StateTransitionMerger());
    }

    public StateUpdater(
            StateTransitionModel transitionModel,
            StateTransitionMerger transitionMerger
    ) {
        this.transitionModel = Objects.requireNonNull(
                transitionModel,
                "transitionModel cannot be null"
        );
        this.transitionMerger = Objects.requireNonNull(
                transitionMerger,
                "transitionMerger cannot be null"
        );
    }

    public StateUpdatePreparation prepare(
            PersonState state,
            List<PersonEvent> currentEvents,
            Instant now,
            StateEvolutionContext context
    ) {
        PersonState currentState = Objects.requireNonNull(
                state,
                "state cannot be null"
        );
        Instant currentTime = Objects.requireNonNull(now, "now cannot be null");
        StateEvolutionContext currentContext = Objects.requireNonNull(
                context,
                "context cannot be null"
        );
        Map<ActivityChannel, PersonEvent> eventsByChannel = indexByChannel(currentEvents);

        settleUntil(currentState, currentTime, currentContext);

        Map<ActivityChannel, ChannelStateEffect> retainedEffects =
                new EnumMap<>(ActivityChannel.class);
        Map<ActivityChannel, PersonEvent> pendingEvents =
                new EnumMap<>(ActivityChannel.class);

        for (ActivityChannel channel : ActivityChannel.values()) {
            PersonEvent currentEvent = eventsByChannel.get(channel);
            ChannelStateEffect existingEffect =
                    currentContext.channelEffects().get(channel);

            if (currentEvent == null) {
                continue;
            }
            if (existingEffect != null
                    && existingEffect.eventId().equals(currentEvent.getId())) {
                retainedEffects.put(channel, existingEffect);
                continue;
            }
            pendingEvents.put(channel, currentEvent);
        }

        return new StateUpdatePreparation(
                new StateEvolutionContext(currentTime, retainedEffects),
                pendingEvents
        );
    }

    public StateEvolutionContext complete(
            StateUpdatePreparation preparation,
            Collection<ChannelStateEffect> evaluatedEffects
    ) {
        StateUpdatePreparation requestedPreparation = Objects.requireNonNull(
                preparation,
                "preparation cannot be null"
        );
        Collection<ChannelStateEffect> requestedEffects = Objects.requireNonNull(
                evaluatedEffects,
                "evaluatedEffects cannot be null"
        );

        Map<ActivityChannel, ChannelStateEffect> effectsByChannel =
                new EnumMap<>(ActivityChannel.class);

        for (ChannelStateEffect effect : requestedEffects) {
            ChannelStateEffect nonNullEffect = Objects.requireNonNull(
                    effect,
                    "effect cannot be null"
            );
            ChannelStateEffect previous = effectsByChannel.put(
                    nonNullEffect.channel(),
                    nonNullEffect
            );
            if (previous != null) {
                throw new IllegalArgumentException(
                        "only one evaluated effect is allowed per channel"
                );
            }
        }

        if (!effectsByChannel.keySet().equals(
                requestedPreparation.pendingEvents().keySet()
        )) {
            throw new IllegalArgumentException(
                    "evaluated effect channels must exactly match pending channels"
            );
        }

        requestedPreparation.pendingEvents().forEach((channel, event) -> {
            EventId expectedEventId = event.getId();
            EventId actualEventId = effectsByChannel.get(channel).eventId();
            if (!expectedEventId.equals(actualEventId)) {
                throw new IllegalArgumentException(
                        "evaluated effect event id does not match pending event"
                );
            }
        });

        Map<ActivityChannel, ChannelStateEffect> completedEffects =
                new EnumMap<>(ActivityChannel.class);
        completedEffects.putAll(
                requestedPreparation.settledContext().channelEffects()
        );
        completedEffects.putAll(effectsByChannel);

        return new StateEvolutionContext(
                requestedPreparation.settledContext().lastUpdatedAt(),
                completedEffects
        );
    }

    private void settleUntil(
            PersonState state,
            Instant now,
            StateEvolutionContext context
    ) {
        Instant lastUpdatedAt = context.lastUpdatedAt();
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
                context.channelEffects().values()
        );
        transitionModel.applyAll(state, mergedTransitions, elapsed);
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
            PersonEvent nonNullEvent = Objects.requireNonNull(
                    event,
                    "event cannot be null"
            );
            PersonEvent previous = eventsByChannel.put(
                    nonNullEvent.getChannel(),
                    nonNullEvent.copy()
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
