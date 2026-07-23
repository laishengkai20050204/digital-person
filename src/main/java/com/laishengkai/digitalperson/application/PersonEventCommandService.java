package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.state.EventEffectRegistration;
import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateUpdatePreparation;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Coordinates event lifecycle changes, effect evaluation and optimistic persistence. */
public final class PersonEventCommandService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            PersonEventCommandService.class
    );

    private final PersonRepository personRepository;
    private final StateUpdater stateUpdater;
    private final EventStateImpactEvaluator evaluator;
    private final StateEvaluationContextAssembler contextAssembler;

    public PersonEventCommandService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            EventStateImpactEvaluator evaluator
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
            EventStateImpactEvaluator evaluator,
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

    /** Starts a realtime event and registers all model-produced independent effects. */
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
            StateUpdatePreparation preparation = prepareExistingEffects(
                    person,
                    workingState,
                    now
            );
            PersonStateSnapshot evaluationState = workingState.snapshot();

            LOGGER.info(
                    "Starting realtime person event command: personId={}, eventId={}, channel={}, activityType={}, expectedVersion={}, pendingEventCount={}",
                    requestedPersonId,
                    requestedEvent.getId(),
                    requestedEvent.getChannel(),
                    requestedEvent.getActivityType(),
                    expectedVersion,
                    preparation.pendingEvents().size()
            );

            return completePendingEvaluations(
                    person,
                    evaluationState,
                    preparation,
                    now
            ).thenCompose(settledContext -> {
                person.startPersonEvent(requestedEvent, now);
                StateEvolutionContext baseContext = stateUpdater.afterTimelineChange(
                        settledContext,
                        person.getCurrentPersonEvents(now),
                        now,
                        eventEndTimes(person)
                );
                StateUpdatePreparation startPreparation = new StateUpdatePreparation(
                        baseContext,
                        Map.of(requestedEvent.getChannel(), requestedEvent)
                );

                return evaluate(person, evaluationState, requestedEvent, now)
                        .thenApply(registration -> {
                            StateEvolutionContext completedContext = stateUpdater.complete(
                                    startPreparation,
                                    List.of(registration)
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
                        });
            }).whenComplete((result, error) -> logCompletion(
                    "start",
                    requestedPersonId,
                    requestedEvent.getId(),
                    expectedVersion,
                    startedAtNanos,
                    error
            ));
        } catch (RuntimeException error) {
            logCompletion(
                    "start",
                    requestedPersonId,
                    requestedEvent.getId(),
                    -1,
                    startedAtNanos,
                    error
            );
            return CompletableFuture.failedFuture(error);
        }
    }

    /** Finishes an event; only effects whose policy depends on event end are removed. */
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
            StateUpdatePreparation preparation = prepareExistingEffects(
                    person,
                    workingState,
                    now
            );
            PersonStateSnapshot evaluationState = workingState.snapshot();

            return completePendingEvaluations(
                    person,
                    evaluationState,
                    preparation,
                    now
            ).handle((settledContext, evaluationError) -> {
                if (evaluationError == null) {
                    return settledContext;
                }
                Throwable failure = unwrapCompletionFailure(evaluationError);
                if (!(failure instanceof PendingEventEvaluationFailure)) {
                    throw new CompletionException(failure);
                }
                LOGGER.warn(
                        "Continuing event finish after pending-effect evaluation failed: personId={}, eventId={}, pendingEventIds={}",
                        requestedPersonId,
                        requestedEventId,
                        preparation.pendingEvents().values().stream()
                                .map(PersonEvent::getId)
                                .toList(),
                        failure.getCause()
                );
                return preparation.settledContext();
            }).thenApply(settledContext -> {
                person.finishPersonEvent(requestedEventId, now, requestedReason, now);
                StateEvolutionContext completedContext = stateUpdater.afterTimelineChange(
                        settledContext,
                        person.getCurrentPersonEvents(now),
                        now,
                        eventEndTimes(person)
                );
                person.commitStateUpdate(workingState, completedContext);
                saveOrThrow(person, expectedVersion);

                PersonEvent committedEvent = person.getPersonEventById(requestedEventId)
                        .orElseThrow();
                return new PersonEventCommandResult(
                        person.getId(),
                        committedEvent,
                        person.getStateSnapshot(),
                        completedContext
                );
            }).whenComplete((result, error) -> logCompletion(
                    "finish",
                    requestedPersonId,
                    requestedEventId,
                    expectedVersion,
                    startedAtNanos,
                    error
            ));
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

    /** Records a completed historical event without replaying current state. */
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

    private StateUpdatePreparation prepareExistingEffects(
            Person person,
            PersonState workingState,
            Instant now
    ) {
        return stateUpdater.prepare(
                workingState,
                person.getCurrentPersonEvents(now),
                now,
                person.getStateEvolutionContext(),
                eventEndTimes(person)
        );
    }

    private CompletionStage<StateEvolutionContext> completePendingEvaluations(
            Person person,
            PersonStateSnapshot state,
            StateUpdatePreparation preparation,
            Instant evaluationTime
    ) {
        final List<CompletableFuture<EventEffectRegistration>> evaluations;
        try {
            evaluations = preparation.eventsToEvaluate().stream()
                    .map(event -> evaluate(
                            person,
                            state,
                            event,
                            evaluationTime
                    ))
                    .map(CompletionStage::toCompletableFuture)
                    .toList();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    new PendingEventEvaluationFailure(error)
            );
        }
        if (evaluations.isEmpty()) {
            return CompletableFuture.completedFuture(preparation.settledContext());
        }

        LOGGER.info(
                "Automatically evaluating pending person events before lifecycle command: personId={}, pendingEventIds={}",
                person.getId(),
                preparation.eventsToEvaluate().stream()
                        .map(PersonEvent::getId)
                        .toList()
        );

        return CompletableFuture.allOf(
                evaluations.toArray(CompletableFuture[]::new)
        ).handle((ignored, evaluationError) -> {
            if (evaluationError != null) {
                throw new PendingEventEvaluationFailure(
                        unwrapCompletionFailure(evaluationError)
                );
            }
            return ignored;
        }).thenApply(ignored -> {
            List<EventEffectRegistration> registrations = evaluations.stream()
                    .map(CompletableFuture::join)
                    .toList();
            return stateUpdater.complete(preparation, registrations);
        });
    }

    private static Throwable unwrapCompletionFailure(Throwable error) {
        Throwable current = Objects.requireNonNull(error, "error cannot be null");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class PendingEventEvaluationFailure extends RuntimeException {
        private PendingEventEvaluationFailure(Throwable cause) {
            super("pending event effect evaluation failed", cause);
        }
    }

    private CompletionStage<EventEffectRegistration> evaluate(
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
                .thenApply(impact -> toRegistration(event, evaluationTime, impact));
    }

    private static EventEffectRegistration toRegistration(
            PersonEvent event,
            Instant evaluationTime,
            EventStateImpact impact
    ) {
        EventStateImpact safeImpact = Objects.requireNonNull(
                impact,
                "evaluator result cannot be null"
        );
        List<RegisteredStateEffect> effects = safeImpact.effects().stream()
                .map(draft -> RegisteredStateEffect.fromDraft(
                        draft,
                        event.getId(),
                        evaluationTime
                ))
                .toList();
        return new EventEffectRegistration(event.getId(), effects);
    }

    private void saveOrThrow(Person person, long expectedVersion) {
        if (!personRepository.save(person, expectedVersion)) {
            throw new PersonVersionConflictException(
                    person.getId(),
                    expectedVersion
            );
        }
    }

    private static Map<EventId, Instant> eventEndTimes(Person person) {
        Map<EventId, Instant> endTimes = new HashMap<>();
        person.getPersonTimeline().getAll().forEach(event ->
                event.getEndTime().ifPresent(endTime ->
                        endTimes.put(event.getId(), endTime)
                )
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
