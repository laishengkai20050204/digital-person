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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stateless deterministic state evolution service over independent state effects. */
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

    public StateUpdatePreparation prepare(
            PersonState state,
            List<PersonEvent> currentEvents,
            Instant now,
            StateEvolutionContext context
    ) {
        return prepare(state, currentEvents, now, context, Map.of());
    }

    /** Settles all effects to {@code now} and finds active events not evaluated yet. */
    public StateUpdatePreparation prepare(
            PersonState state,
            List<PersonEvent> currentEvents,
            Instant now,
            StateEvolutionContext context,
            Map<EventId, Instant> eventEndTimes
    ) {
        PersonState currentState = Objects.requireNonNull(state, "state cannot be null");
        Instant currentTime = Objects.requireNonNull(now, "now cannot be null");
        StateEvolutionContext currentContext = Objects.requireNonNull(
                context,
                "context cannot be null"
        );
        Map<EventId, Instant> safeEndTimes = copyEndTimes(eventEndTimes);
        Map<ActivityChannel, PersonEvent> eventsByChannel = indexByChannel(currentEvents);

        LOGGER.debug(
                "Preparing state evolution: updateTime={}, currentEventCount={}, effectCount={}, evaluatedEventCount={}, previousUpdateTime={}, knownEventEndCount={}",
                currentTime,
                eventsByChannel.size(),
                currentContext.effects().size(),
                currentContext.evaluatedEventIds().size(),
                currentContext.lastUpdatedAt(),
                safeEndTimes.size()
        );

        settleUntil(currentState, currentTime, currentContext, safeEndTimes);

        Map<EffectId, RegisteredStateEffect> retainedEffects = new HashMap<>();
        currentContext.effects().forEach((effectId, effect) -> {
            if (effect.isActiveAt(currentTime, safeEndTimes)) {
                retainedEffects.put(effectId, effect);
            }
        });

        Set<EventId> currentEventIds = new HashSet<>();
        eventsByChannel.values().forEach(event -> currentEventIds.add(event.getId()));
        Set<EventId> retainedEvaluatedEventIds = new HashSet<>(
                currentContext.evaluatedEventIds()
        );
        retainedEvaluatedEventIds.retainAll(currentEventIds);

        Map<ActivityChannel, PersonEvent> pendingEvents = new EnumMap<>(
                ActivityChannel.class
        );
        eventsByChannel.forEach((channel, event) -> {
            if (!retainedEvaluatedEventIds.contains(event.getId())) {
                pendingEvents.put(channel, event);
            }
        });

        LOGGER.debug(
                "Prepared state evolution: retainedEffectCount={}, retainedEvaluatedEventCount={}, pendingChannels={}",
                retainedEffects.size(),
                retainedEvaluatedEventIds.size(),
                pendingEvents.keySet()
        );

        return new StateUpdatePreparation(
                new StateEvolutionContext(
                        currentTime,
                        retainedEffects,
                        retainedEvaluatedEventIds
                ),
                pendingEvents
        );
    }

    /** Combines evaluated event registrations with all retained effects. */
    public StateEvolutionContext complete(
            StateUpdatePreparation preparation,
            Collection<EventEffectRegistration> registrations
    ) {
        StateUpdatePreparation requestedPreparation = Objects.requireNonNull(
                preparation,
                "preparation cannot be null"
        );
        Collection<EventEffectRegistration> requestedRegistrations = Objects.requireNonNull(
                registrations,
                "registrations cannot be null"
        );

        Map<EventId, EventEffectRegistration> registrationsByEvent = new HashMap<>();
        for (EventEffectRegistration registration : requestedRegistrations) {
            EventEffectRegistration nonNullRegistration = Objects.requireNonNull(
                    registration,
                    "registration cannot be null"
            );
            if (registrationsByEvent.put(
                    nonNullRegistration.eventId(),
                    nonNullRegistration
            ) != null) {
                throw new IllegalArgumentException(
                        "only one effect registration is allowed per event"
                );
            }
        }

        Set<EventId> pendingEventIds = new HashSet<>();
        requestedPreparation.pendingEvents().values()
                .forEach(event -> pendingEventIds.add(event.getId()));
        if (!registrationsByEvent.keySet().equals(pendingEventIds)) {
            throw new IllegalArgumentException(
                    "effect registrations must exactly match pending events"
            );
        }

        Map<EffectId, RegisteredStateEffect> completedEffects = new HashMap<>(
                requestedPreparation.settledContext().effects()
        );
        for (EventEffectRegistration registration : requestedRegistrations) {
            for (RegisteredStateEffect effect : registration.effects()) {
                if (completedEffects.putIfAbsent(effect.effectId(), effect) != null) {
                    throw new IllegalArgumentException(
                            "duplicate effect id: " + effect.effectId()
                    );
                }
            }
        }

        Set<EventId> evaluatedEventIds = new HashSet<>(
                requestedPreparation.settledContext().evaluatedEventIds()
        );
        evaluatedEventIds.addAll(registrationsByEvent.keySet());

        LOGGER.debug(
                "Completed state evolution context: updateTime={}, effectCount={}, evaluatedEventCount={}",
                requestedPreparation.settledContext().lastUpdatedAt(),
                completedEffects.size(),
                evaluatedEventIds.size()
        );

        return new StateEvolutionContext(
                requestedPreparation.settledContext().lastUpdatedAt(),
                completedEffects,
                evaluatedEventIds
        );
    }

    /** Prunes effects and evaluation markers after an event timeline mutation. */
    public StateEvolutionContext afterTimelineChange(
            StateEvolutionContext context,
            List<PersonEvent> currentEvents,
            Instant now,
            Map<EventId, Instant> eventEndTimes
    ) {
        StateEvolutionContext currentContext = Objects.requireNonNull(
                context,
                "context cannot be null"
        );
        Instant currentTime = Objects.requireNonNull(now, "now cannot be null");
        Map<EventId, Instant> safeEndTimes = copyEndTimes(eventEndTimes);

        Map<EffectId, RegisteredStateEffect> retainedEffects = new HashMap<>();
        currentContext.effects().forEach((effectId, effect) -> {
            if (effect.isActiveAt(currentTime, safeEndTimes)) {
                retainedEffects.put(effectId, effect);
            }
        });

        Set<EventId> currentEventIds = new HashSet<>();
        indexByChannel(currentEvents).values()
                .forEach(event -> currentEventIds.add(event.getId()));
        Set<EventId> retainedEvaluatedEventIds = new HashSet<>(
                currentContext.evaluatedEventIds()
        );
        retainedEvaluatedEventIds.retainAll(currentEventIds);

        return new StateEvolutionContext(
                currentContext.lastUpdatedAt(),
                retainedEffects,
                retainedEvaluatedEventIds
        );
    }

    private void settleUntil(
            PersonState state,
            Instant now,
            StateEvolutionContext context,
            Map<EventId, Instant> eventEndTimes
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
                context.effects().values(),
                eventEndTimes
        );
        Instant intervalStart = lastUpdatedAt;
        int appliedIntervalCount = 0;

        for (Instant intervalEnd : boundaries) {
            Instant currentIntervalStart = intervalStart;
            List<StateEffect> activeEffects = context.effects().values().stream()
                    .filter(effect -> effect.isActiveAt(
                            currentIntervalStart,
                            eventEndTimes
                    ))
                    .map(effect -> (StateEffect) effect)
                    .toList();
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
                "Settled state effects: elapsedMs={}, effectCount={}, boundaryCount={}, appliedIntervalCount={}",
                elapsed.toMillis(),
                context.effects().size(),
                boundaries.size(),
                appliedIntervalCount
        );
    }

    private static List<Instant> settlementBoundaries(
            Instant start,
            Instant end,
            Collection<RegisteredStateEffect> effects,
            Map<EventId, Instant> eventEndTimes
    ) {
        List<Instant> boundaries = new ArrayList<>();
        for (RegisteredStateEffect effect : effects) {
            if (effect.startsAt().isAfter(start) && effect.startsAt().isBefore(end)) {
                boundaries.add(effect.startsAt());
            }
            effect.effectiveEndTime(eventEndTimes)
                    .filter(boundary -> boundary.isAfter(start) && boundary.isBefore(end))
                    .ifPresent(boundaries::add);
        }
        boundaries.add(end);
        return boundaries.stream()
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static Map<EventId, Instant> copyEndTimes(
            Map<EventId, Instant> eventEndTimes
    ) {
        Map<EventId, Instant> copy = new HashMap<>();
        Objects.requireNonNull(eventEndTimes, "eventEndTimes cannot be null")
                .forEach((eventId, endTime) -> copy.put(
                        Objects.requireNonNull(eventId, "eventId cannot be null"),
                        Objects.requireNonNull(endTime, "event end time cannot be null")
                ));
        return Map.copyOf(copy);
    }

    /** Indexes current events while enforcing the one-event-per-channel invariant. */
    private static Map<ActivityChannel, PersonEvent> indexByChannel(
            List<PersonEvent> currentEvents
    ) {
        List<PersonEvent> events = List.copyOf(Objects.requireNonNull(
                currentEvents,
                "currentEvents cannot be null"
        ));
        Map<ActivityChannel, PersonEvent> eventsByChannel = new EnumMap<>(
                ActivityChannel.class
        );
        for (PersonEvent event : events) {
            PersonEvent nonNullEvent = Objects.requireNonNull(
                    event,
                    "event cannot be null"
            );
            if (eventsByChannel.put(
                    nonNullEvent.getChannel(),
                    nonNullEvent.copy()
            ) != null) {
                throw new IllegalArgumentException(
                        "only one current event is allowed per activity channel"
                );
            }
        }
        return eventsByChannel;
    }
}
