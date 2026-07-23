package com.laishengkai.digitalperson.application;

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
import com.laishengkai.digitalperson.state.StateTransitionEvaluator;
import com.laishengkai.digitalperson.state.StateUpdatePreparation;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Executes one complete person-state update use case. */
public final class UpdatePersonStateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            UpdatePersonStateService.class
    );

    private final PersonRepository personRepository;
    private final StateUpdater stateUpdater;
    private final EventStateImpactEvaluator evaluator;
    private final StateEvaluationContextAssembler contextAssembler;

    /** Compatibility constructor for active-only evaluators. */
    public UpdatePersonStateService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            StateTransitionEvaluator evaluator
    ) {
        this(
                personRepository,
                stateUpdater,
                adapt(evaluator),
                DefaultStateEvaluationContextAssembler.withoutExternalSources()
        );
    }

    public UpdatePersonStateService(
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

    /** Compatibility constructor for active-only evaluators with custom context. */
    public UpdatePersonStateService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            StateTransitionEvaluator evaluator,
            StateEvaluationContextAssembler contextAssembler
    ) {
        this(personRepository, stateUpdater, adapt(evaluator), contextAssembler);
    }

    public UpdatePersonStateService(
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

    public CompletionStage<StateUpdateResult> update(PersonId personId, Instant now) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        Instant currentTime = Objects.requireNonNull(now, "now cannot be null");
        long startedAtNanos = System.nanoTime();

        LOGGER.info(
                "Starting person state update: personId={}, updateTime={}",
                requestedPersonId,
                currentTime
        );

        try {
            VersionedPerson loadedPerson = personRepository.findById(requestedPersonId)
                    .orElseThrow(() -> new PersonNotFoundException(requestedPersonId));
            Person person = loadedPerson.person().copy();
            long expectedVersion = loadedPerson.version();

            PersonState workingState = person.getState();
            StateUpdatePreparation preparation = stateUpdater.prepare(
                    workingState,
                    person.getCurrentPersonEvents(currentTime),
                    currentTime,
                    person.getStateEvolutionContext(),
                    eventEndTimes(person)
            );
            PersonStateSnapshot evaluationSnapshot = workingState.snapshot();

            LOGGER.debug(
                    "Prepared person state update: personId={}, expectedVersion={}, pendingChannels={}, retainedEffectCount={}",
                    requestedPersonId,
                    expectedVersion,
                    preparation.pendingEvents().keySet(),
                    preparation.settledContext().effects().size()
            );

            List<CompletableFuture<EventEffectRegistration>> evaluations =
                    preparation.eventsToEvaluate().stream()
                            .map(event -> evaluate(
                                    person,
                                    evaluationSnapshot,
                                    event,
                                    currentTime
                            ))
                            .map(CompletionStage::toCompletableFuture)
                            .toList();

            CompletableFuture<Void> allEvaluations = CompletableFuture.allOf(
                    evaluations.toArray(CompletableFuture[]::new)
            );

            CompletionStage<StateUpdateResult> updateStage = allEvaluations.thenApply(ignored -> {
                List<EventEffectRegistration> registrations = new ArrayList<>(
                        evaluations.size()
                );
                for (CompletableFuture<EventEffectRegistration> evaluation : evaluations) {
                    registrations.add(evaluation.join());
                }

                StateEvolutionContext completedContext = stateUpdater.complete(
                        preparation,
                        registrations
                );
                person.commitStateUpdate(workingState, completedContext);
                if (!personRepository.save(person, expectedVersion)) {
                    throw new PersonVersionConflictException(
                            requestedPersonId,
                            expectedVersion
                    );
                }

                return new StateUpdateResult(
                        person.getId(),
                        person.getStateSnapshot(),
                        completedContext
                );
            });

            return updateStage.whenComplete((result, error) -> {
                long elapsedMillis = elapsedMillis(startedAtNanos);
                if (error == null) {
                    LOGGER.info(
                            "Completed person state update: personId={}, expectedVersion={}, evaluatedEventCount={}, elapsedMs={}",
                            requestedPersonId,
                            expectedVersion,
                            evaluations.size(),
                            elapsedMillis
                    );
                } else {
                    LOGGER.warn(
                            "Person state update failed: personId={}, expectedVersion={}, elapsedMs={}",
                            requestedPersonId,
                            expectedVersion,
                            elapsedMillis,
                            error
                    );
                }
            });
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Person state update failed before asynchronous evaluation: personId={}, elapsedMs={}",
                    requestedPersonId,
                    elapsedMillis(startedAtNanos),
                    error
            );
            throw error;
        }
    }

    private CompletionStage<EventEffectRegistration> evaluate(
            Person person,
            PersonStateSnapshot state,
            PersonEvent event,
            Instant evaluationTime
    ) {
        LOGGER.debug(
                "Evaluating event effects: eventId={}, channel={}, activityType={}",
                event.getId(),
                event.getChannel(),
                event.getActivityType()
        );

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

        LOGGER.debug(
                "Evaluated event effects: eventId={}, channel={}, effectCount={}",
                event.getId(),
                event.getChannel(),
                effects.size()
        );
        return new EventEffectRegistration(event.getId(), effects);
    }

    private static EventStateImpactEvaluator adapt(StateTransitionEvaluator evaluator) {
        StateTransitionEvaluator requestedEvaluator = Objects.requireNonNull(
                evaluator,
                "evaluator cannot be null"
        );
        return context -> Objects.requireNonNull(
                        requestedEvaluator.evaluate(context),
                        "evaluator stage cannot be null"
                )
                .thenApply(transitions -> EventStateImpact.activeOnly(
                        List.copyOf(Objects.requireNonNull(
                                transitions,
                                "evaluator result cannot be null"
                        ))
                ));
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

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
