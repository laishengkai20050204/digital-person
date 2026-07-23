package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.state.ChannelStateEffect;
import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.ResidualStateEffect;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransitionEvaluator;
import com.laishengkai.digitalperson.state.StateUpdatePreparation;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates realtime person-event lifecycle changes with deterministic state
 * settlement, model evaluation and optimistic persistence.
 *
 * <p>Realtime starts must use an event whose start time equals the command time.
 * Historical events use {@link #recordHistorical(PersonId, PersonEvent, Instant)}
 * and never retroactively change the current short-term state.</p>
 */
public final class PersonEventCommandService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            PersonEventCommandService.class
    );

    private final PersonRepository personRepository;
    private final StateUpdater stateUpdater;
    private final StateTransitionEvaluator evaluator;
    private final StateEvaluationContextAssembler contextAssembler;

    public PersonEventCommandService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            StateTransitionEvaluator evaluator
    ) {
        this(
                personRepository,
                stateUpdater,
                evaluator,
                DefaultStateEvaluationContextAssembler.withoutExternalSources()
        );
    }

    public PersonEventCommandService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            StateTransitionEvaluator evaluator,
            StateEvaluationContextAssembler contextAssembler
    ) {
        this.personRepository = Objects.requireNonNull(
                personRepository,
                "personRepository cannot be null"
        );
        this.stateUpdater = Objects.requireNonNull(
                stateUpdater,
                "stateUpdater cannot be null"
        );
        this.evaluator = Objects.requireNonNull(
                evaluator,
                "evaluator cannot be null"
        );
        this.contextAssembler = Objects.requireNonNull(
                contextAssembler,
                "contextAssembler cannot be null"
        );
    }

    /**
     * Starts a realtime event, replacing any open event in the same activity
     * channel. A replaced event's model-evaluated aftermath becomes an
     * independent residual effect instead of occupying that channel.
     */
    public CompletionStage<PersonEventCommandResult> start(
            PersonId personId,
            PersonEvent event,
            Instant commandTime
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        PersonEvent requestedEvent = Objects.requireNonNull(
                event,
                "event cannot be null"
        ).copy();
        Instant now = Objects.requireNonNull(
                commandTime,
                "commandTime cannot be null"
        );
        if (!requestedEvent.isOpen()) {
            throw new IllegalArgumentException("a realtime start event must be open-ended");
        }
        if (!requestedEvent.getStartTime().equals(now)) {
            throw new IllegalArgumentException(
                    "realtime event startTime must equal commandTime; "
                            + "use recordHistorical for past events"
            );
        }

        long startedAtNanos = System.nanoTime();
        try {
            VersionedPerson loaded = load(requestedPersonId);
            Person person = loaded.person().copy();
            long expectedVersion = loaded.version();
            PersonState workingState = person.getState();
            StateUpdatePreparation settled = settleExistingEffects(
                    person,
                    workingState,
                    now
            );

            StateEvolutionContext baseContext = detachChannelEffect(
                    settled.settledContext(),
                    requestedEvent.getChannel(),
                    now
            );
            person.startPersonEvent(requestedEvent, now);
            StateUpdatePreparation startPreparation = new StateUpdatePreparation(
                    baseContext,
                    Map.of(requestedEvent.getChannel(), requestedEvent)
            );
            PersonStateSnapshot evaluationState = workingState.snapshot();

            LOGGER.info(
                    "Starting realtime person event command: personId={}, eventId={}, channel={}, activityType={}, expectedVersion={}",
                    requestedPersonId,
                    requestedEvent.getId(),
                    requestedEvent.getChannel(),
                    requestedEvent.getActivityType(),
                    expectedVersion
            );

            return evaluate(person, evaluationState, requestedEvent, now)
                    .thenApply(effect -> {
                        StateEvolutionContext completedContext = stateUpdater.complete(
                                startPreparation,
                                List.of(effect)
                        );
                        person.commitStateUpdate(workingState, completedContext);
                        saveOrThrow(person, expectedVersion);
                        PersonEvent committedEvent = person.getPersonEventById(
                                requestedEvent.getId()
                        ).orElseThrow();
                        return new PersonEventCommandResult(
                                person.getId(),
                                committedEvent,
                                person.getStateSnapshot(),
                                completedContext
                        );
                    })
                    .whenComplete((result, error) -> logCompletion(
                            "start",
                            requestedPersonId,
                            requestedEvent.getId(),
                            expectedVersion,
                            startedAtNanos,
                            error
                    ));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    /**
     * Finishes an open realtime event after applying its activity-bound effect
     * exactly through {@code commandTime}. The activity channel is released and
     * any model-evaluated aftermath is retained independently.
     */
    public CompletionStage<PersonEventCommandResult> finish(
            PersonId personId,
            EventId eventId,
            EventEndReason reason,
            Instant commandTime
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        EventId requestedEventId = Objects.requireNonNull(
                eventId,
                "eventId cannot be null"
        );
        EventEndReason requestedReason = Objects.requireNonNull(
                reason,
                "reason cannot be null"
        );
        if (requestedReason == EventEndReason.REPLACED) {
            throw new IllegalArgumentException(
                    "REPLACED is owned by start replacement semantics"
            );
        }
        Instant now = Objects.requireNonNull(
                commandTime,
                "commandTime cannot be null"
        );
        long startedAtNanos = System.nanoTime();

        try {
            VersionedPerson loaded = load(requestedPersonId);
            Person person = loaded.person().copy();
            long expectedVersion = loaded.version();
            PersonEvent event = person.getPersonEventById(requestedEventId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "person event does not exist: " + requestedEventId
                    ));
            if (!event.isOpen()) {
                throw new IllegalStateException("only an open event can be finished");
            }

            PersonState workingState = person.getState();
            StateUpdatePreparation settled = settleExistingEffects(
                    person,
                    workingState,
                    now
            );
            person.finishPersonEvent(requestedEventId, now, requestedReason, now);
            StateEvolutionContext completedContext = detachChannelEffect(
                    settled.settledContext(),
                    event.getChannel(),
                    now
            );
            person.commitStateUpdate(workingState, completedContext);
            saveOrThrow(person, expectedVersion);

            PersonEvent committedEvent = person.getPersonEventById(requestedEventId)
                    .orElseThrow();
            PersonEventCommandResult result = new PersonEventCommandResult(
                    person.getId(),
                    committedEvent,
                    person.getStateSnapshot(),
                    completedContext
            );
            logCompletion(
                    "finish",
                    requestedPersonId,
                    requestedEventId,
                    expectedVersion,
                    startedAtNanos,
                    null
            );
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException error) {
            logCompletion(
                    "finish",
                    requestedPersonId,
                    requestedEventId,
                    -1,
                    startedAtNanos,
                    error
            );
            return CompletableFuture.failedFuture(error);
        }
    }

    /**
     * Records a completed historical event without settling or replaying state.
     * This is the explicit non-retroactive path for events discovered after they
     * already happened.
     */
    public CompletionStage<PersonEventCommandResult> recordHistorical(
            PersonId personId,
            PersonEvent event,
            Instant registrationTime
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        PersonEvent requestedEvent = Objects.requireNonNull(
                event,
                "event cannot be null"
        ).copy();
        Instant now = Objects.requireNonNull(
                registrationTime,
                "registrationTime cannot be null"
        );
        if (requestedEvent.getEndTime().isEmpty()) {
            throw new IllegalArgumentException("a historical event must have an end time");
        }
        long startedAtNanos = System.nanoTime();

        try {
            VersionedPerson loaded = load(requestedPersonId);
            Person person = loaded.person().copy();
            long expectedVersion = loaded.version();
            PersonStateSnapshot stateBefore = person.getStateSnapshot();
            StateEvolutionContext contextBefore = person.getStateEvolutionContext();

            person.recordPersonEvent(requestedEvent, now);
            saveOrThrow(person, expectedVersion);

            PersonEvent committedEvent = person.getPersonEventById(
                    requestedEvent.getId()
            ).orElseThrow();
            PersonEventCommandResult result = new PersonEventCommandResult(
                    person.getId(),
                    committedEvent,
                    stateBefore,
                    contextBefore
            );
            logCompletion(
                    "recordHistorical",
                    requestedPersonId,
                    requestedEvent.getId(),
                    expectedVersion,
                    startedAtNanos,
                    null
            );
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException error) {
            logCompletion(
                    "recordHistorical",
                    requestedPersonId,
                    requestedEvent.getId(),
                    -1,
                    startedAtNanos,
                    error
            );
            return CompletableFuture.failedFuture(error);
        }
    }

    private VersionedPerson load(PersonId personId) {
        return personRepository.findById(personId)
                .orElseThrow(() -> new PersonNotFoundException(personId));
    }

    private StateUpdatePreparation settleExistingEffects(
            Person person,
            PersonState workingState,
            Instant now
    ) {
        StateEvolutionContext existingContext = person.getStateEvolutionContext();
        StateUpdatePreparation preparation = stateUpdater.prepare(
                workingState,
                person.getCurrentPersonEvents(now),
                now,
                existingContext,
                cachedEffectEndTimes(person, existingContext)
        );
        if (!preparation.pendingEvents().isEmpty()) {
            throw new UnsettledPersonEventException(
                    person.getId(),
                    preparation.pendingEvents().keySet()
            );
        }
        return preparation;
    }

    private CompletionStage<ChannelStateEffect> evaluate(
            Person person,
            PersonStateSnapshot state,
            PersonEvent event,
            Instant evaluationTime
    ) {
        CompletionStage<StateEvaluationContext> contextStage = Objects.requireNonNull(
                contextAssembler.assemble(
                        person,
                        state,
                        event.copy(),
                        evaluationTime
                ),
                "contextAssembler stage cannot be null"
        );
        return contextStage.thenCompose(context -> Objects.requireNonNull(
                        evaluator.evaluate(Objects.requireNonNull(
                                context,
                                "assembled context cannot be null"
                        )),
                        "evaluator stage cannot be null"
                ))
                .thenApply(impact -> toChannelEffect(event, impact));
    }

    private static ChannelStateEffect toChannelEffect(
            PersonEvent event,
            EventStateImpact impact
    ) {
        EventStateImpact safeImpact = Objects.requireNonNull(
                impact,
                "evaluator result cannot be null"
        );
        return new ChannelStateEffect(
                event.getChannel(),
                event.getId(),
                safeImpact.activeTransitions(),
                safeImpact.aftermath()
        );
    }

    private void saveOrThrow(Person person, long expectedVersion) {
        if (!personRepository.save(person, expectedVersion)) {
            throw new PersonVersionConflictException(
                    person.getId(),
                    expectedVersion
            );
        }
    }

    /**
     * Releases one activity channel and materializes its independent aftermath,
     * while preserving all other activity and residual effects.
     */
    private static StateEvolutionContext detachChannelEffect(
            StateEvolutionContext context,
            ActivityChannel channel,
            Instant transitionTime
    ) {
        StateEvolutionContext requestedContext = Objects.requireNonNull(
                context,
                "context cannot be null"
        );
        ActivityChannel requestedChannel = Objects.requireNonNull(
                channel,
                "channel cannot be null"
        );
        Instant now = Objects.requireNonNull(
                transitionTime,
                "transitionTime cannot be null"
        );

        Map<ActivityChannel, ChannelStateEffect> retained = new EnumMap<>(
                ActivityChannel.class
        );
        retained.putAll(requestedContext.channelEffects());
        ChannelStateEffect detached = retained.remove(requestedChannel);

        Map<EventId, ResidualStateEffect> residuals = new HashMap<>(
                requestedContext.residualEffects()
        );
        if (detached != null && detached.hasAftermath()) {
            ResidualStateEffect residual = ResidualStateEffect.fromPlan(
                    detached.eventId(),
                    now,
                    detached.aftermath()
            );
            if (residuals.putIfAbsent(residual.sourceEventId(), residual) != null) {
                throw new IllegalStateException(
                        "residual effect already exists for source event: "
                                + residual.sourceEventId()
                );
            }
        }
        return new StateEvolutionContext(
                requestedContext.lastUpdatedAt(),
                retained,
                residuals
        );
    }

    private static Map<EventId, Instant> cachedEffectEndTimes(
            Person person,
            StateEvolutionContext context
    ) {
        Map<EventId, Instant> endTimes = new HashMap<>();
        context.channelEffects().values().forEach(effect ->
                person.getPersonEventById(effect.eventId())
                        .flatMap(PersonEvent::getEndTime)
                        .ifPresent(endTime -> endTimes.put(effect.eventId(), endTime))
        );
        return Map.copyOf(endTimes);
    }

    private static void logCompletion(
            String command,
            PersonId personId,
            EventId eventId,
            long expectedVersion,
            long startedAtNanos,
            Throwable error
    ) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAtNanos
        );
        if (error == null) {
            LOGGER.info(
                    "Completed person event command: command={}, personId={}, eventId={}, expectedVersion={}, elapsedMs={}",
                    command,
                    personId,
                    eventId,
                    expectedVersion,
                    elapsedMillis
            );
        } else {
            LOGGER.warn(
                    "Person event command failed: command={}, personId={}, eventId={}, expectedVersion={}, elapsedMs={}, errorType={}",
                    command,
                    personId,
                    eventId,
                    expectedVersion,
                    elapsedMillis,
                    error.getClass().getSimpleName()
            );
        }
    }
}
