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
 *
 * <p>The service contains no person-specific mutable fields and can therefore
 * be shared safely between different person aggregates.</p>
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

    /**
     * Settles previously active effects up to {@code now} and detects current
     * events that still require external evaluation.
     *
     * <p>This compatibility overload assumes no cached effect ended between
     * the previous update and {@code now}. Application workflows that own an
     * event timeline should call the overload accepting effect end times.</p>
     */
    public StateUpdatePreparation prepare(
            PersonState state,
            List<PersonEvent> currentEvents,
            Instant now,
            StateEvolutionContext context
    ) {
        return prepare(state, currentEvents, now, context, Map.of());
    }

    /**
     * Settles previously active effects up to {@code now} and detects current
     * events that still require external evaluation.
     *
     * <p>The supplied {@link PersonState} is a caller-owned working copy. It is
     * updated deterministically, while the returned preparation object remains
     * immutable and safe to pass across asynchronous boundaries.</p>
     *
     * <p>{@code cachedEffectEndTimes} contains known end boundaries for events
     * referenced by the persisted channel effects. Effects are merged and
     * applied separately for each interval so an event that ended before
     * {@code now} cannot continue changing state after its actual end time.</p>
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
                "Preparing state evolution: updateTime={}, currentEventCount={}, activeEffectCount={}, previousUpdateTime={}, knownEffectEndCount={}",
                currentTime,
                eventsByChannel.size(),
                currentContext.channelEffects().size(),
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

        LOGGER.debug(
                "Prepared state evolution: retainedChannels={}, pendingChannels={}",
                retainedEffects.keySet(),
                pendingEvents.keySet()
        );

        return new StateUpdatePreparation(
                new StateEvolutionContext(currentTime, retainedEffects),
                pendingEvents
        );
    }

    /**
     * Validates asynchronous evaluation results and combines them with retained
     * effects from {@link #prepare(PersonState, List, Instant, StateEvolutionContext)}.
     *
     * <p>Every pending channel must have exactly one result and that result must
     * reference the event that was prepared for the same channel.</p>
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
                "Completed state evolution context: updateTime={}, activeChannels={}",
                requestedPreparation.settledContext().lastUpdatedAt(),
                completedEffects.keySet()
        );

        return new StateEvolutionContext(
                requestedPreparation.settledContext().lastUpdatedAt(),
                completedEffects
        );
    }

    /** Applies cached effects only across intervals in which their events remained active. */
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
            List<ChannelStateEffect> activeEffects = context.channelEffects()
                    .values()
                    .stream()
                    .filter(effect -> remainsActiveAfter(
                            effect,
                            intervalStart,
                            effectEndTimes
                    ))
                    .toList();
            List<StateTransition> mergedTransitions = transitionMerger.merge(
                    activeEffects
            );
            Duration intervalDuration = Duration.between(intervalStart, intervalEnd);

            if (!intervalDuration.isZero() && !mergedTransitions.isEmpty()) {
                transitionModel.applyAll(state, mergedTransitions, intervalDuration);
                appliedIntervalCount++;
            }
            intervalStart = intervalEnd;
        }

        LOGGER.debug(
                "Settled cached state effects: elapsedMs={}, activeEffectCount={}, boundaryCount={}, appliedIntervalCount={}",
                elapsed.toMillis(),
                context.channelEffects().size(),
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
        boundaries.add(end);
        boundaries.sort(Comparator.naturalOrder());
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
        Map<EventId, Instant> copy = new java.util.HashMap<>();
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
