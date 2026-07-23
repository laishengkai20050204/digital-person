package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.FinishActivityCommand;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionContext;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionModel;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.activity.StartActivityCommand;
import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Settles state, asks the activity model for a pure plan, validates and applies it,
 * evaluates newly started events, and saves the aggregate once with optimistic locking.
 */
public final class PersonActivityDecisionService {
    public static final int MAX_OBSERVATION_LENGTH = 4_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PersonActivityDecisionService.class
    );

    private final PersonRepository personRepository;
    private final StateUpdater stateUpdater;
    private final PersonActivityDecisionModel activityDecisionModel;
    private final PersonActivityDecisionContextAssembler activityContextAssembler;
    private final EventStateImpactEvaluator effectEvaluator;
    private final StateEvaluationContextAssembler effectContextAssembler;

    public PersonActivityDecisionService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            PersonActivityDecisionModel activityDecisionModel,
            EventStateImpactEvaluator effectEvaluator
    ) {
        this(
                personRepository,
                stateUpdater,
                activityDecisionModel,
                DefaultPersonActivityDecisionContextAssembler.withoutExternalSources(),
                effectEvaluator,
                DefaultStateEvaluationContextAssembler.withoutExternalSources()
        );
    }

    public PersonActivityDecisionService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            PersonActivityDecisionModel activityDecisionModel,
            PersonActivityDecisionContextAssembler activityContextAssembler,
            EventStateImpactEvaluator effectEvaluator,
            StateEvaluationContextAssembler effectContextAssembler
    ) {
        this.personRepository = Objects.requireNonNull(
                personRepository,
                "personRepository cannot be null"
        );
        this.stateUpdater = Objects.requireNonNull(
                stateUpdater,
                "stateUpdater cannot be null"
        );
        this.activityDecisionModel = Objects.requireNonNull(
                activityDecisionModel,
                "activityDecisionModel cannot be null"
        );
        this.activityContextAssembler = Objects.requireNonNull(
                activityContextAssembler,
                "activityContextAssembler cannot be null"
        );
        this.effectEvaluator = Objects.requireNonNull(
                effectEvaluator,
                "effectEvaluator cannot be null"
        );
        this.effectContextAssembler = Objects.requireNonNull(
                effectContextAssembler,
                "effectContextAssembler cannot be null"
        );
    }

    public CompletionStage<PersonActivityDecisionResult> decide(
            PersonId personId,
            Instant decisionTime
    ) {
        return decide(personId, "", decisionTime);
    }

    public CompletionStage<PersonActivityDecisionResult> decide(
            PersonId personId,
            String observation,
            Instant decisionTime
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        Instant now = Objects.requireNonNull(
                decisionTime,
                "decisionTime cannot be null"
        );
        String normalizedObservation = normalizeObservation(observation);
        long startedAtNanos = System.nanoTime();

        try {
            VersionedPerson loaded = personRepository.findById(requestedPersonId)
                    .orElseThrow(() -> new PersonNotFoundException(requestedPersonId));
            Person person = loaded.person().copy();
            long expectedVersion = loaded.version();
            PersonState workingState = person.getState();
            StateUpdatePreparation preparation = stateUpdater.prepare(
                    workingState,
                    person.getCurrentPersonEvents(now),
                    now,
                    person.getStateEvolutionContext(),
                    eventEndTimes(person)
            );
            PersonStateSnapshot decisionState = workingState.snapshot();

            LOGGER.info(
                    "Starting autonomous activity decision: personId={}, expectedVersion={}, activePersonEventCount={}, pendingEventCount={}, observationPresent={}",
                    requestedPersonId,
                    expectedVersion,
                    person.getCurrentPersonEvents(now).size(),
                    preparation.pendingEvents().size(),
                    !normalizedObservation.isBlank()
            );

            return completePendingEvaluations(
                    person,
                    decisionState,
                    preparation,
                    now
            ).thenCompose(settledContext -> assembleDecisionContext(
                    person,
                    decisionState,
                    settledContext,
                    normalizedObservation,
                    now
            ).thenCompose(context -> decidePlan(context)
                    .thenCompose(plan -> applyAndEvaluate(
                            person,
                            decisionState,
                            settledContext,
                            plan,
                            now
                    ))
            )).thenApply(applied -> {
                person.commitStateUpdate(workingState, applied.completedContext());
                if (!personRepository.save(person, expectedVersion)) {
                    throw new PersonVersionConflictException(
                            requestedPersonId,
                            expectedVersion
                    );
                }
                return new PersonActivityDecisionResult(
                        person.getId(),
                        applied.plan(),
                        applied.startedEvents(),
                        applied.finishedEvents(),
                        person.getStateSnapshot(),
                        applied.completedContext(),
                        now,
                        now.plus(Duration.ofMinutes(
                                applied.plan().nextReviewMinutes()
                        ))
                );
            }).whenComplete((result, error) -> {
                long elapsedMillis = elapsedMillis(startedAtNanos);
                if (error == null) {
                    LOGGER.info(
                            "Completed autonomous activity decision: personId={}, expectedVersion={}, commandCount={}, startedEventCount={}, finishedEventCount={}, nextReviewMinutes={}, elapsedMs={}",
                            requestedPersonId,
                            expectedVersion,
                            result.plan().commands().size(),
                            result.startedEvents().size(),
                            result.finishedEvents().size(),
                            result.plan().nextReviewMinutes(),
                            elapsedMillis
                    );
                } else {
                    LOGGER.warn(
                            "Autonomous activity decision failed: personId={}, expectedVersion={}, elapsedMs={}",
                            requestedPersonId,
                            expectedVersion,
                            elapsedMillis,
                            error
                    );
                }
            });
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Autonomous activity decision failed before model invocation: personId={}, elapsedMs={}",
                    requestedPersonId,
                    elapsedMillis(startedAtNanos),
                    error
            );
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletionStage<PersonActivityDecisionContext> assembleDecisionContext(
            Person person,
            PersonStateSnapshot state,
            StateEvolutionContext evolution,
            String observation,
            Instant now
    ) {
        return Objects.requireNonNull(
                activityContextAssembler.assemble(
                        person,
                        state,
                        evolution,
                        observation,
                        now
                ),
                "activityContextAssembler stage cannot be null"
        ).thenApply(context -> Objects.requireNonNull(
                context,
                "assembled activity context cannot be null"
        ));
    }

    private CompletionStage<PersonActivityDecisionPlan> decidePlan(
            PersonActivityDecisionContext context
    ) {
        return Objects.requireNonNull(
                activityDecisionModel.decide(context),
                "activityDecisionModel stage cannot be null"
        ).thenApply(plan -> Objects.requireNonNull(
                plan,
                "activityDecisionModel result cannot be null"
        ));
    }

    private CompletionStage<AppliedPlan> applyAndEvaluate(
            Person person,
            PersonStateSnapshot evaluationState,
            StateEvolutionContext settledContext,
            PersonActivityDecisionPlan plan,
            Instant now
    ) {
        validateFinishCommands(person, plan.finishCommands(), now);

        LinkedHashMap<EventId, PersonEvent> finishedEvents = new LinkedHashMap<>();
        for (FinishActivityCommand finish : plan.finishCommands()) {
            person.finishPersonEvent(finish.eventId(), now, finish.reason(), now);
            finishedEvents.put(
                    finish.eventId(),
                    person.getPersonEventById(finish.eventId()).orElseThrow()
            );
        }

        List<PersonEvent> startedEvents = new ArrayList<>();
        for (StartActivityCommand start : plan.startCommands()) {
            List<PersonEvent> replacementCandidates = person.getCurrentPersonEvents(now)
                    .stream()
                    .filter(event -> event.getChannel() == start.channel())
                    .toList();
            PersonEvent event = new PersonEvent(
                    EventId.random(),
                    start.activityType(),
                    start.title(),
                    start.location(),
                    TimeRange.openEnded(now),
                    start.participants(),
                    start.notes()
            );
            try {
                person.startPersonEvent(event, now);
            } catch (IllegalArgumentException | IllegalStateException error) {
                throw new InvalidPersonActivityDecisionException(
                        "START conflicts with the current event timeline in channel "
                                + start.channel(),
                        error
                );
            }
            PersonEvent committedStart = person.getPersonEventById(event.getId()).orElseThrow();
            startedEvents.add(committedStart);
            replacementCandidates.forEach(replaced -> finishedEvents.put(
                    replaced.getId(),
                    person.getPersonEventById(replaced.getId()).orElseThrow()
            ));
        }

        StateEvolutionContext baseContext = stateUpdater.afterTimelineChange(
                settledContext,
                person.getCurrentPersonEvents(now),
                now,
                eventEndTimes(person)
        );
        if (startedEvents.isEmpty()) {
            return CompletableFuture.completedFuture(new AppliedPlan(
                    plan,
                    List.of(),
                    List.copyOf(finishedEvents.values()),
                    baseContext
            ));
        }

        Map<ActivityChannel, PersonEvent> pendingStarts = new EnumMap<>(
                ActivityChannel.class
        );
        startedEvents.forEach(event -> pendingStarts.put(event.getChannel(), event));
        StateUpdatePreparation startPreparation = new StateUpdatePreparation(
                baseContext,
                pendingStarts
        );
        List<CompletableFuture<EventEffectRegistration>> evaluations = startedEvents.stream()
                .map(event -> evaluateEffect(
                        person,
                        evaluationState,
                        baseContext,
                        event,
                        now
                ))
                .map(CompletionStage::toCompletableFuture)
                .toList();

        return CompletableFuture.allOf(
                evaluations.toArray(CompletableFuture[]::new)
        ).thenApply(ignored -> {
            List<EventEffectRegistration> registrations = evaluations.stream()
                    .map(CompletableFuture::join)
                    .toList();
            StateEvolutionContext completedContext = stateUpdater.complete(
                    startPreparation,
                    registrations
            );
            return new AppliedPlan(
                    plan,
                    startedEvents,
                    List.copyOf(finishedEvents.values()),
                    completedContext
            );
        });
    }

    private static void validateFinishCommands(
            Person person,
            List<FinishActivityCommand> commands,
            Instant now
    ) {
        for (FinishActivityCommand finish : commands) {
            PersonEvent event = person.getPersonEventById(finish.eventId())
                    .orElseThrow(() -> new InvalidPersonActivityDecisionException(
                            "FINISH references an unknown person event: " + finish.eventId()
                    ));
            if (!event.isOpen() || !event.contains(now)) {
                throw new InvalidPersonActivityDecisionException(
                        "FINISH references an event that is not active: " + finish.eventId()
                );
            }
        }
    }

    private CompletionStage<StateEvolutionContext> completePendingEvaluations(
            Person person,
            PersonStateSnapshot state,
            StateUpdatePreparation preparation,
            Instant evaluationTime
    ) {
        List<CompletableFuture<EventEffectRegistration>> evaluations =
                preparation.eventsToEvaluate().stream()
                        .map(event -> evaluateEffect(
                                person,
                                state,
                                preparation.settledContext(),
                                event,
                                evaluationTime
                        ))
                        .map(CompletionStage::toCompletableFuture)
                        .toList();
        if (evaluations.isEmpty()) {
            return CompletableFuture.completedFuture(preparation.settledContext());
        }
        return CompletableFuture.allOf(
                evaluations.toArray(CompletableFuture[]::new)
        ).thenApply(ignored -> stateUpdater.complete(
                preparation,
                evaluations.stream()
                        .map(CompletableFuture::join)
                        .toList()
        ));
    }

    private CompletionStage<EventEffectRegistration> evaluateEffect(
            Person person,
            PersonStateSnapshot state,
            StateEvolutionContext evolution,
            PersonEvent event,
            Instant evaluationTime
    ) {
        CompletionStage<StateEvaluationContext> contextStage = Objects.requireNonNull(
                effectContextAssembler.assemble(
                        person,
                        state,
                        evolution,
                        event.copy(),
                        evaluationTime
                ),
                "effectContextAssembler stage cannot be null"
        );
        return contextStage.thenCompose(context -> Objects.requireNonNull(
                        effectEvaluator.evaluate(Objects.requireNonNull(
                                context,
                                "assembled effect context cannot be null"
                        )),
                        "effectEvaluator stage cannot be null"
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
                "effectEvaluator result cannot be null"
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

    private static Map<EventId, Instant> eventEndTimes(Person person) {
        Map<EventId, Instant> endTimes = new HashMap<>();
        person.getPersonTimeline().getAll().forEach(event ->
                event.getEndTime().ifPresent(endTime ->
                        endTimes.put(event.getId(), endTime)
                )
        );
        return Map.copyOf(endTimes);
    }

    private static String normalizeObservation(String observation) {
        String normalized = Objects.requireNonNullElse(observation, "").strip();
        if (normalized.length() > MAX_OBSERVATION_LENGTH) {
            throw new IllegalArgumentException(
                    "observation cannot exceed " + MAX_OBSERVATION_LENGTH + " characters"
            );
        }
        return normalized;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private record AppliedPlan(
            PersonActivityDecisionPlan plan,
            List<PersonEvent> startedEvents,
            List<PersonEvent> finishedEvents,
            StateEvolutionContext completedContext
    ) {
        private AppliedPlan {
            plan = Objects.requireNonNull(plan, "plan cannot be null");
            startedEvents = List.copyOf(startedEvents);
            finishedEvents = List.copyOf(finishedEvents);
            completedContext = Objects.requireNonNull(
                    completedContext,
                    "completedContext cannot be null"
            );
        }
    }
}
