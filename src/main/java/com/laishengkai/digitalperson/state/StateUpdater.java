package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** Stateless deterministic state evolution service over natural and event effects. */
public final class StateUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(StateUpdater.class);
    private static final Duration NATURAL_STEP = Duration.ofMinutes(15);

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

    /** Compatibility path that settles only registered effects. */
    public StateUpdatePreparation prepare(
            PersonState state,
            List<PersonEvent> currentEvents,
            Instant now,
            StateEvolutionContext context,
            Map<EventId, Instant> eventEndTimes
    ) {
        return prepareInternal(
                state,
                currentEvents,
                now,
                context,
                eventEndTimes,
                null
        );
    }

    /**
     * Production path that continuously settles Java-owned physiological and baseline
     * evolution before model-produced event effects.
     */
    public StateUpdatePreparation prepareWithNaturalEvolution(
            PersonId personId,
            ZoneId timeZone,
            PersonState state,
            List<PersonEvent> currentEvents,
            List<PersonEvent> personEvents,
            Instant now,
            StateEvolutionContext context,
            Map<EventId, Instant> eventEndTimes
    ) {
        NaturalInput naturalInput = new NaturalInput(
                Objects.requireNonNull(personId, "personId cannot be null"),
                Objects.requireNonNull(timeZone, "timeZone cannot be null"),
                List.copyOf(Objects.requireNonNull(
                        personEvents,
                        "personEvents cannot be null"
                ))
        );
        return prepareInternal(
                state,
                currentEvents,
                now,
                context,
                eventEndTimes,
                naturalInput
        );
    }

    private StateUpdatePreparation prepareInternal(
            PersonState state,
            List<PersonEvent> currentEvents,
            Instant now,
            StateEvolutionContext context,
            Map<EventId, Instant> eventEndTimes,
            NaturalInput naturalInput
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
                "Preparing state evolution: updateTime={}, currentEventCount={}, effectCount={}, evaluatedEventCount={}, previousUpdateTime={}, knownEventEndCount={}, naturalEvolution={}",
                currentTime,
                eventsByChannel.size(),
                currentContext.effects().size(),
                currentContext.evaluatedEventIds().size(),
                currentContext.lastUpdatedAt(),
                safeEndTimes.size(),
                naturalInput != null
        );

        if (naturalInput != null) {
            settleNaturalUntil(
                    currentState,
                    currentTime,
                    currentContext,
                    naturalInput
            );
        }
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

    private void settleNaturalUntil(
            PersonState state,
            Instant now,
            StateEvolutionContext context,
            NaturalInput input
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
        if (now.equals(lastUpdatedAt)) {
            return;
        }

        Instant intervalStart = lastUpdatedAt;
        int stepCount = 0;
        while (intervalStart.isBefore(now)) {
            Instant candidateEnd = intervalStart.plus(NATURAL_STEP);
            Instant intervalEnd = candidateEnd.isBefore(now) ? candidateEnd : now;
            Duration duration = Duration.between(intervalStart, intervalEnd);
            Instant sampleTime = intervalStart.plusNanos(duration.toNanos() / 2L);
            NaturalMetrics metrics = naturalMetrics(input.personEvents(), sampleTime);
            applyNaturalStep(
                    state,
                    input,
                    metrics,
                    sampleTime,
                    duration
            );
            intervalStart = intervalEnd;
            stepCount++;
        }

        LOGGER.debug(
                "Settled natural state evolution: personId={}, elapsedMs={}, stepCount={}",
                input.personId(),
                Duration.between(lastUpdatedAt, now).toMillis(),
                stepCount
        );
    }

    private void applyNaturalStep(
            PersonState state,
            NaturalInput input,
            NaturalMetrics metrics,
            Instant sampleTime,
            Duration duration
    ) {
        ActivityType primary = metrics.primaryActivity();
        boolean sleeping = primary == ActivityType.SLEEP;
        boolean eating = primary == ActivityType.EAT;
        double sleepDebtHours = Math.max(0.0, 8.0 - metrics.sleepHoursLast24());
        double awakeHours = metrics.awakeHours();
        LocalDate localDate = sampleTime.atZone(input.timeZone()).toLocalDate();

        double hungerTarget;
        double hungerRate;
        if (eating) {
            hungerTarget = 0.05;
            hungerRate = 2.5;
        } else {
            double hoursSinceMeal = metrics.hoursSinceLastMeal();
            hungerTarget = clamp(0.08 + Math.max(0.0, hoursSinceMeal - 1.0) * 0.12);
            hungerRate = sleeping ? 0.10 : 0.22;
        }
        applyNaturalTarget(
                state,
                input,
                localDate,
                StateDimension.HUNGER,
                hungerTarget,
                hungerRate,
                duration
        );

        double sleepinessTarget;
        double sleepinessRate;
        if (sleeping) {
            sleepinessTarget = 0.05;
            sleepinessRate = 0.80;
        } else {
            double circadian = circadianSleepiness(
                    sampleTime.atZone(input.timeZone()).getHour()
            );
            double awakePressure = clampRange((awakeHours - 8.0) / 10.0 * 0.35, 0.0, 0.35);
            double debtPressure = Math.min(0.30, sleepDebtHours * 0.06);
            sleepinessTarget = clampRange(
                    circadian + awakePressure + debtPressure,
                    0.05,
                    0.95
            );
            sleepinessRate = 0.30;
        }
        applyNaturalTarget(
                state,
                input,
                localDate,
                StateDimension.SLEEPINESS,
                sleepinessTarget,
                sleepinessRate,
                duration
        );

        double energyTarget;
        double energyRate;
        double fatigueTarget;
        double fatigueRate;
        if (sleeping) {
            energyTarget = clampRange(0.90 - sleepDebtHours * 0.035, 0.68, 0.90);
            energyRate = 0.20;
            fatigueTarget = clampRange(0.05 + sleepDebtHours * 0.03, 0.05, 0.25);
            fatigueRate = 0.22;
        } else if (primary == ActivityType.EXERCISE) {
            energyTarget = 0.30;
            energyRate = 0.30;
            fatigueTarget = 0.75;
            fatigueRate = 0.35;
        } else if (primary == ActivityType.STUDY || primary == ActivityType.WORK) {
            energyTarget = clampRange(0.55 - Math.max(0.0, awakeHours - 8.0) * 0.015, 0.35, 0.55);
            energyRate = 0.10;
            fatigueTarget = clampRange(0.22 + Math.max(0.0, awakeHours - 8.0) * 0.03, 0.22, 0.65);
            fatigueRate = 0.12;
        } else if (primary == ActivityType.REST) {
            energyTarget = 0.65;
            energyRate = 0.12;
            fatigueTarget = 0.12;
            fatigueRate = 0.12;
        } else {
            energyTarget = 0.58;
            energyRate = 0.06;
            fatigueTarget = clampRange(0.18 + Math.max(0.0, awakeHours - 8.0) * 0.02, 0.18, 0.55);
            fatigueRate = 0.08;
        }
        applyNaturalTarget(
                state,
                input,
                localDate,
                StateDimension.ENERGY,
                energyTarget,
                energyRate,
                duration
        );
        applyNaturalTarget(
                state,
                input,
                localDate,
                StateDimension.FATIGUE,
                fatigueTarget,
                fatigueRate,
                duration
        );

        if (sleeping) {
            applyNaturalTarget(state, input, localDate, StateDimension.MENTAL_LOAD, 0.08, 0.30, duration);
            applyNaturalTarget(state, input, localDate, StateDimension.FOCUS, 0.15, 0.30, duration);
            applyNaturalTarget(state, input, localDate, StateDimension.MOTIVATION, 0.50, 0.12, duration);
        } else if (primary != ActivityType.STUDY && primary != ActivityType.WORK) {
            double mentalLoadTarget = primary == ActivityType.REST
                    || primary == ActivityType.ENTERTAINMENT
                    ? 0.18
                    : 0.25;
            applyNaturalTarget(state, input, localDate, StateDimension.MENTAL_LOAD, mentalLoadTarget, 0.08, duration);
            applyNaturalTarget(state, input, localDate, StateDimension.FOCUS, 0.55, 0.08, duration);
            applyNaturalTarget(state, input, localDate, StateDimension.MOTIVATION, 0.60, 0.04, duration);
        }
    }

    private void applyNaturalTarget(
            PersonState state,
            NaturalInput input,
            LocalDate localDate,
            StateDimension dimension,
            double baseTarget,
            double baseRate,
            Duration duration
    ) {
        String seedPrefix = input.personId() + "|" + localDate + "|" + dimension.name();
        double targetOffset = (stableUnit(seedPrefix + "|target") - 0.5) * 0.06;
        double rateMultiplier = 0.90 + stableUnit(seedPrefix + "|rate") * 0.20;
        double target = dimension.clamp(baseTarget + targetOffset);
        double rate = baseRate * rateMultiplier;
        transitionModel.applyTarget(state, dimension, target, rate, duration);
    }

    private static NaturalMetrics naturalMetrics(
            List<PersonEvent> events,
            Instant sampleTime
    ) {
        PersonEvent primary = events.stream()
                .filter(event -> event.getChannel() == ActivityChannel.PRIMARY)
                .filter(event -> event.contains(sampleTime))
                .max(Comparator.comparing(PersonEvent::getStartTime))
                .orElse(null);

        Instant windowStart = sampleTime.minus(Duration.ofHours(24));
        double sleepHours = events.stream()
                .filter(event -> event.getActivityType() == ActivityType.SLEEP)
                .mapToLong(event -> overlapSeconds(event, windowStart, sampleTime))
                .sum() / 3_600.0;

        double awakeHours;
        if (primary != null && primary.getActivityType() == ActivityType.SLEEP) {
            awakeHours = 0.0;
        } else {
            awakeHours = events.stream()
                    .filter(event -> event.getActivityType() == ActivityType.SLEEP)
                    .map(PersonEvent::getEndTime)
                    .flatMap(java.util.Optional::stream)
                    .filter(end -> !end.isAfter(sampleTime))
                    .max(Comparator.naturalOrder())
                    .map(end -> Duration.between(end, sampleTime).toSeconds() / 3_600.0)
                    .orElse(8.0);
        }

        double hoursSinceMeal;
        if (primary != null && primary.getActivityType() == ActivityType.EAT) {
            hoursSinceMeal = 0.0;
        } else {
            hoursSinceMeal = events.stream()
                    .filter(event -> event.getActivityType() == ActivityType.EAT)
                    .map(PersonEvent::getEndTime)
                    .flatMap(java.util.Optional::stream)
                    .filter(end -> !end.isAfter(sampleTime))
                    .max(Comparator.naturalOrder())
                    .map(end -> Duration.between(end, sampleTime).toSeconds() / 3_600.0)
                    .orElse(6.0);
        }

        return new NaturalMetrics(
                primary == null ? null : primary.getActivityType(),
                sleepHours,
                Math.max(0.0, awakeHours),
                Math.max(0.0, hoursSinceMeal)
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

    private static long overlapSeconds(
            PersonEvent event,
            Instant windowStart,
            Instant windowEnd
    ) {
        Instant start = event.getStartTime().isAfter(windowStart)
                ? event.getStartTime()
                : windowStart;
        Instant rawEnd = event.getEndTime().orElse(windowEnd);
        Instant end = rawEnd.isBefore(windowEnd) ? rawEnd : windowEnd;
        return end.isAfter(start) ? Duration.between(start, end).toSeconds() : 0L;
    }

    private static double circadianSleepiness(int hour) {
        if (hour < 5) {
            return 0.82;
        }
        if (hour < 8) {
            return 0.45;
        }
        if (hour < 20) {
            return 0.15;
        }
        if (hour < 22) {
            return 0.35;
        }
        return 0.65;
    }

    private static double clamp(double value) {
        return clampRange(value, 0.0, 1.0);
    }

    private static double clampRange(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double stableUnit(String seed) {
        byte[] bytes = seed.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return (hash & Long.MAX_VALUE) / (double) Long.MAX_VALUE;
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

    private record NaturalInput(
            PersonId personId,
            ZoneId timeZone,
            List<PersonEvent> personEvents
    ) {
    }

    private record NaturalMetrics(
            ActivityType primaryActivity,
            double sleepHoursLast24,
            double awakeHours,
            double hoursSinceLastMeal
    ) {
    }
}
