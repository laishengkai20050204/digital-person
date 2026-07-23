package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless deterministic state evolution service.
 *
 * <p>Activity effects and independent residual effects are settled over exact
 * time intervals. The service contains no person-specific mutable fields and is
 * safe to share between aggregates.</p>
 */
public final class StateUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(StateUpdater.class);

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

    /** Compatibility overload for workflows without known activity end boundaries. */
    public StateUpdatePreparation prepare(
            PersonState state,
            List<PersonEvent> currentEvents,
            Instant now,
            StateEvolutionContext context
    ) {
        return prepare(state, currentEvents, now, context, Map.of());
    }

    /**
     * Settles all cached activity and residual effects to {@code now}, retains
     * still-active effects, and identifies current activities that need model
     * evaluation.
     */
    public StateUpdatePreparation prepare(
            PersonState state,
            List<PersonEvent> currentEvents,
            Instant now,
            StateEvolutionContext context,
            Map<EventId, Instant> cachedEffectEndTimes
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
        Map<EventId, Instant> effectEndTimes = copyEndTimes(cachedEffectEndTimes);
        Map<ActivityChannel, PersonEvent> eventsByChannel = indexByChannel(currentEvents);

        LOGGER.debug(
                "Preparing state evolution: updateTime={}, currentEventCount={}, activeActivityEffectCount={}, residualEffectCount={}, previousUpdateTime={}, knownEffectEndCount={}",
                currentTime,
                eventsByChannel.size(),
                currentContext.channelEffects().size(),
                currentContext.residualEffects().size(),
                currentContext.lastUpdatedAt(),
                effectEndTimes.size()
        );

        settleUntil(currentState, currentTime, currentContext, effectEndTimes);

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

        Map<EventId, ResidualStateEffect> retainedResidualEffects = new HashMap<>();
        currentContext.residualEffects().forEach((sourceEventId, effect) -> {
            if (effect.endsAt().isAfter(currentTime)) {
                retainedResidualEffects.put(sourceEventId, effect);
            }
        });

        LOGGER.debug(
                "Prepared state evolution: retainedChannels={}, pendingChannels={}, retainedResidualEffectCount={}",
                retainedEffects.keySet(),
                pendingEvents.keySet(),
                retainedResidualEffects.size()
        );

        return new StateUpdatePreparation(
                new StateEvolutionContext(
                        currentTime,
                        retainedEffects,
                        retainedResidualEffects
                ),
                pendingEvents
        );
    }

    /**
     * Validates asynchronous activity evaluations and combines them with all
     * retained activity and residual effects.
     */
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

        LOGGER.debug(
                "Completed state evolution context: updateTime={}, activeChannels={}, residualEffectCount={}",
                requestedPreparation.settledContext().lastUpdatedAt(),
                completedEffects.keySet(),
                requestedPreparation.settledContext().residualEffects().size()
        );

        return new StateEvolutionContext(
                requestedPreparation.settledContext().lastUpdatedAt(),
                completedEffects,
                requestedPreparation.settledContext().residualEffects()
        );
    }

    /** Applies activity and residual effects across every exact lifecycle interval. */
    private void settleUntil(
            PersonState state,
            Instant now,
            StateEvolutionContext context,
            Map<EventId, Instant> effectEndTimes
    ) {
        Instant lastUpdatedAt = context.lastUpdatedAt();
        if (lastUpdatedAt == null) {
            LOGGER.trace("Skipping state settlement because no previous update exists");
            return;
        }
        if (now.isBefore(lastUpdatedAt)) {
            throw new IllegalArgumentException(
                    "now cannot be before the previous update time"
            );
        }

        Duration elapsed = Duration.between(lastUpdatedAt, now);
        if (elapsed.isZero()) {
            LOGGER.trace("Skipping state settlement because no time elapsed");
            return;
        }

        List<Instant> boundaries = settlementBoundaries(
                lastUpdatedAt,
                now,
                context,
                effectEndTimes
        );
        Instant intervalStart = lastUpdatedAt;
        int appliedIntervalCount = 0;

        for (Instant intervalEnd : boundaries) {
            Instant currentIntervalStart = intervalStart;
            List<StateEffect> activeEffects = new ArrayList<>();
            context.channelEffects().values().stream()
                    .filter(effect -> remainsActiveAfter(
                            effect,
                            currentIntervalStart,
                            effectEndTimes
                    ))
                    .forEach(activeEffects::add);
            context.residualEffects().values().stream()
                    .filter(effect -> effect.isActiveAt(currentIntervalStart))
                    .forEach(activeEffects::add);

            List<StateTransition> mergedTransitions = transitionMerger.merge(
                    activeEffects
            );
            Duration intervalDuration = Duration.between(
                    currentIntervalStart,
                    intervalEnd
            );

            if (!intervalDuration.isZero() && !mergedTransitions.isEmpty()) {
                transitionModel.applyAll(state, mergedTransitions, intervalDuration);
                appliedIntervalCount++;
            }
            intervalStart = intervalEnd;
        }

        LOGGER.debug(
                "Settled cached state effects: elapsedMs={}, activityEffectCount={}, residualEffectCount={}, boundaryCount={}, appliedIntervalCount={}",
                elapsed.toMillis(),
                context.channelEffects().size(),
                context.residualEffects().size(),
                boundaries.size(),
                appliedIntervalCount
        );
    }

    private static List<Instant> settlementBoundaries(
            Instant start,
            Instant end,
            StateEvolutionContext context,
            Map<EventId, Instant> effectEndTimes
    ) {
        List<Instant> boundaries = new ArrayList<>();
        context.channelEffects().values().stream()
                .map(ChannelStateEffect::eventId)
                .map(effectEndTimes::get)
                .filter(Objects::nonNull)
                .filter(boundary -> boundary.isAfter(start) && boundary.isBefore(end))
                .distinct()
                .forEach(boundaries::add);
        context.residualEffects().values().forEach(effect -> {
            if (effect.startsAt().isAfter(start) && effect.startsAt().isBefore(end)) {
                boundaries.add(effect.startsAt());
            }
            if (effect.endsAt().isAfter(start) && effect.endsAt().isBefore(end)) {
                boundaries.add(effect.endsAt());
            }
        });
        boundaries.add(end);
        boundaries = boundaries.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        return List.copyOf(boundaries);
    }

    private static boolean remainsActiveAfter(
            ChannelStateEffect effect,
            Instant intervalStart,
            Map<EventId, Instant> effectEndTimes
    ) {
        Instant endTime = effectEndTimes.get(effect.eventId());
        return endTime == null || endTime.isAfter(intervalStart);
    }

    private static Map<EventId, Instant> copyEndTimes(
            Map<EventId, Instant> cachedEffectEndTimes
    ) {
        Map<EventId, Instant> copy = new HashMap<>();
        Objects.requireNonNull(
                cachedEffectEndTimes,
                "cachedEffectEndTimes cannot be null"
        ).forEach((eventId, endTime) -> copy.put(
                Objects.requireNonNull(eventId, "effect event id cannot be null"),
                Objects.requireNonNull(endTime, "effect end time cannot be null")
        ));
        return Map.copyOf(copy);
    }

    /** Indexes current events while enforcing the one-event-per-channel invariant. */
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
