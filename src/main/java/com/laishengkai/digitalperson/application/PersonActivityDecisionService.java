package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.FinishActivityCommand;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionContext;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionModel;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.activity.StartActivityCommand;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateUpdatePreparation;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
    private final PersonStateEvolutionCoordinator stateEvolution;
    private final PersonActivityDecisionModel activityDecisionModel;
    private final PersonActivityDecisionContextAssembler activityContextAssembler;
    private final Clock clock;

    public PersonActivityDecisionService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            PersonActivityDecisionModel activityDecisionModel,
            EventStateImpactEvaluator effectEvaluator
    ) {
        this(
                personRepository,
                new PersonStateEvolutionCoordinator(
                        stateUpdater,
                        effectEvaluator,
                        DefaultStateEvaluationContextAssembler.withoutExternalSources()
                ),
                activityDecisionModel,
                DefaultPersonActivityDecisionContextAssembler.withoutExternalSources(),
                Clock.systemUTC()
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
        this(
                personRepository,
                new PersonStateEvolutionCoordinator(
                        stateUpdater,
                        effectEvaluator,
                        effectContextAssembler
                ),
                activityDecisionModel,
                activityContextAssembler,
                Clock.systemUTC()
        );
    }

    public PersonActivityDecisionService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            PersonActivityDecisionModel activityDecisionModel,
            PersonActivityDecisionContextAssembler activityContextAssembler,
            EventStateImpactEvaluator effectEvaluator,
            StateEvaluationContextAssembler effectContextAssembler,
            Clock clock
    ) {
        this(
                personRepository,
                new PersonStateEvolutionCoordinator(
                        stateUpdater,
                        effectEvaluator,
                        effectContextAssembler
                ),
                activityDecisionModel,
                activityContextAssembler,
                clock
        );
    }

    public PersonActivityDecisionService(
            PersonRepository personRepository,
            PersonStateEvolutionCoordinator stateEvolution,
            PersonActivityDecisionModel activityDecisionModel,
            PersonActivityDecisionContextAssembler activityContextAssembler,
            Clock clock
    ) {
        this.personRepository = Objects.requireNonNull(
                personRepository,
                "personRepository cannot be null"
        );
        this.stateEvolution = Objects.requireNonNull(
                stateEvolution,
                "stateEvolution cannot be null"
        );
        this.activityDecisionModel = Objects.requireNonNull(
                activityDecisionModel,
                "activityDecisionModel cannot be null"
        );
        this.activityContextAssembler = Objects.requireNonNull(
                activityContextAssembler,
                "activityContextAssembler cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    public CompletionStage<PersonActivityDecisionResult> decide(
            PersonId personId,
            Instant decisionTime
    ) {
        return decide(personId, "", decisionTime, Instant.MAX);
    }

    public CompletionStage<PersonActivityDecisionResult> decide(
            PersonId personId,
            String observation,
            Instant decisionTime
    ) {
        return decide(personId, observation, decisionTime, Instant.MAX);
    }

    public CompletionStage<PersonActivityDecisionResult> decide(
            PersonId personId,
            Instant decisionTime,
            Instant deadline
    ) {
        return decide(personId, "", decisionTime, deadline);
    }

    public CompletionStage<PersonActivityDecisionResult> decide(
            PersonId personId,
            String observation,
            Instant decisionTime,
            Instant deadline
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        Instant now = Objects.requireNonNull(
                decisionTime,
                "decisionTime cannot be null"
        );
        Instant requiredDeadline = requireDeadline(deadline, now);
        String normalizedObservation = normalizeObservation(observation);
        long startedAtNanos = System.nanoTime();
        Consumer<String> checkpoint = phase -> ensureBeforeDeadline(
                requiredDeadline,
                phase
        );

        try {
            checkpoint.accept("person load");
            VersionedPerson loaded = personRepository.findById(requestedPersonId)
                    .orElseThrow(() -> new PersonNotFoundException(requestedPersonId));
            Person person = loaded.person().copy();
            long expectedVersion = loaded.version();
            PersonState workingState = person.getState();
            StateUpdatePreparation preparation = stateEvolution.prepare(
                    person,
                    workingState,
                    now,
                    checkpoint
            );
            PersonStateSnapshot decisionState = workingState.snapshot();

            LOGGER.info(
                    "Starting autonomous activity decision: personId={}, expectedVersion={}, activePersonEventCount={}, pendingEventCount={}, observationPresent={}, deadline={}",
                    requestedPersonId,
                    expectedVersion,
                    person.getCurrentPersonEvents(now).size(),
                    preparation.pendingEvents().size(),
                    !normalizedObservation.isBlank(),
                    requiredDeadline
            );

            return withDeadline(
                    stateEvolution.completePending(
                            person,
                            decisionState,
                            preparation,
                            now,
                            checkpoint
                    ),
                    requiredDeadline,
                    "pending event evaluation"
            ).thenCompose(settledContext -> {
                checkpoint.accept("activity context assembly");
                return assembleDecisionContext(
                        person,
                        decisionState,
                        settledContext,
                        normalizedObservation,
                        now,
                        requiredDeadline,
                        checkpoint
                ).thenCompose(context -> {
                    checkpoint.accept("activity model invocation");
                    return decidePlan(
                            context,
                            requiredDeadline,
                            checkpoint
                    )
                            .thenCompose(plan -> {
                                checkpoint.accept("activity plan application");
                                return applyAndEvaluate(
                                        person,
                                        decisionState,
                                        settledContext,
                                        plan,
                                        now,
                                        requiredDeadline,
                                        checkpoint
                                );
                            });
                });
            }).thenApply(applied -> {
                checkpoint.accept("aggregate commit");
                person.commitStateUpdate(workingState, applied.completedContext());
                checkpoint.accept("aggregate persistence");
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
            }).whenComplete((result, error) -> logCompletion(
                    requestedPersonId,
                    expectedVersion,
                    startedAtNanos,
                    result,
                    error
            ));
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
            Instant now,
            Instant deadline,
            Consumer<String> checkpoint
    ) {
        checkpoint.accept("activity context source loading");
        CompletionStage<PersonActivityDecisionContext> stage = Objects.requireNonNull(
                activityContextAssembler.assemble(
                        person,
                        state,
                        evolution,
                        observation,
                        now
                ),
                "activityContextAssembler stage cannot be null"
        );
        return withDeadline(stage, deadline, "activity context source loading")
                .thenApply(context -> {
            checkpoint.accept("activity context completion");
            return Objects.requireNonNull(
                    context,
                    "assembled activity context cannot be null"
            );
        });
    }

    private CompletionStage<PersonActivityDecisionPlan> decidePlan(
            PersonActivityDecisionContext context,
            Instant deadline,
            Consumer<String> checkpoint
    ) {
        checkpoint.accept("activity model invocation");
        CompletionStage<PersonActivityDecisionPlan> stage = Objects.requireNonNull(
                activityDecisionModel.decide(context),
                "activityDecisionModel stage cannot be null"
        );
        return withDeadline(stage, deadline, "activity model invocation")
                .thenApply(plan -> {
            checkpoint.accept("activity model response");
            return Objects.requireNonNull(
                    plan,
                    "activityDecisionModel result cannot be null"
            );
        });
    }

    private CompletionStage<AppliedPlan> applyAndEvaluate(
            Person person,
            PersonStateSnapshot evaluationState,
            StateEvolutionContext settledContext,
            PersonActivityDecisionPlan plan,
            Instant now,
            Instant deadline,
            Consumer<String> checkpoint
    ) {
        checkpoint.accept("activity plan validation");
        validateFinishCommands(person, plan.finishCommands(), now);

        LinkedHashMap<EventId, PersonEvent> finishedEvents = new LinkedHashMap<>();
        for (FinishActivityCommand finish : plan.finishCommands()) {
            checkpoint.accept("finish command application");
            person.finishPersonEvent(finish.eventId(), now, finish.reason(), now);
            finishedEvents.put(
                    finish.eventId(),
                    person.getPersonEventById(finish.eventId()).orElseThrow()
            );
        }

        List<PersonEvent> startedEvents = new ArrayList<>();
        for (StartActivityCommand start : plan.startCommands()) {
            checkpoint.accept("start command application");
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

        StateEvolutionContext baseContext = stateEvolution.afterTimelineChange(
                person,
                settledContext,
                now,
                checkpoint
        );
        CompletionStage<StateEvolutionContext> evaluationStage =
                stateEvolution.evaluateStartedEvents(
                        person,
                        evaluationState,
                        baseContext,
                        startedEvents,
                        now,
                        checkpoint
                );
        return withDeadline(
                evaluationStage,
                deadline,
                "new event effect evaluation"
        ).thenApply(completedContext -> new AppliedPlan(
                plan,
                startedEvents,
                List.copyOf(finishedEvents.values()),
                completedContext
        ));
    }

    private <T> CompletionStage<T> withDeadline(
            CompletionStage<T> stage,
            Instant deadline,
            String phase
    ) {
        CompletionStage<T> safeStage = Objects.requireNonNull(
                stage,
                "stage cannot be null"
        );
        ensureBeforeDeadline(deadline, phase);
        CompletableFuture<T> source = safeStage.toCompletableFuture();
        if (Instant.MAX.equals(deadline)) {
            return source.thenApply(value -> {
                ensureBeforeDeadline(deadline, phase);
                return value;
            });
        }

        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            source.cancel(true);
            return CompletableFuture.failedFuture(
                    new PersonActivityDecisionDeadlineExceededException(deadline, phase)
            );
        }

        CompletableFuture<T> guarded = new CompletableFuture<>();
        long delayNanos;
        try {
            delayNanos = Math.max(1L, remaining.toNanos());
        } catch (ArithmeticException overflow) {
            delayNanos = Long.MAX_VALUE;
        }
        CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS)
                .execute(() -> {
                    PersonActivityDecisionDeadlineExceededException timeout =
                            new PersonActivityDecisionDeadlineExceededException(
                                    deadline,
                                    phase
                            );
                    if (guarded.completeExceptionally(timeout)) {
                        source.cancel(true);
                    }
                });
        source.whenComplete((value, error) -> {
            if (error != null) {
                guarded.completeExceptionally(unwrapCompletionFailure(error));
                return;
            }
            try {
                ensureBeforeDeadline(deadline, phase);
                guarded.complete(value);
            } catch (RuntimeException timeout) {
                guarded.completeExceptionally(timeout);
            }
        });
        guarded.whenComplete((ignored, error) -> {
            if (guarded.isCancelled()) {
                source.cancel(true);
            }
        });
        return guarded;
    }

    private static Throwable unwrapCompletionFailure(Throwable error) {
        Throwable current = Objects.requireNonNull(error, "error cannot be null");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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

    private static String normalizeObservation(String observation) {
        String normalized = Objects.requireNonNullElse(observation, "").strip();
        if (normalized.length() > MAX_OBSERVATION_LENGTH) {
            throw new IllegalArgumentException(
                    "observation cannot exceed " + MAX_OBSERVATION_LENGTH + " characters"
            );
        }
        return normalized;
    }

    private static Instant requireDeadline(Instant deadline, Instant decisionTime) {
        Instant value = Objects.requireNonNull(deadline, "deadline cannot be null");
        if (!value.isAfter(decisionTime)) {
            throw new IllegalArgumentException("deadline must be after decisionTime");
        }
        return value;
    }

    private void ensureBeforeDeadline(Instant deadline, String phase) {
        if (!clock.instant().isBefore(deadline)) {
            throw new PersonActivityDecisionDeadlineExceededException(deadline, phase);
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static void logCompletion(
            PersonId personId,
            long expectedVersion,
            long startedAtNanos,
            PersonActivityDecisionResult result,
            Throwable error
    ) {
        long elapsedMillis = elapsedMillis(startedAtNanos);
        if (error == null) {
            LOGGER.info(
                    "Completed autonomous activity decision: personId={}, expectedVersion={}, commandCount={}, startedEventCount={}, finishedEventCount={}, nextReviewMinutes={}, elapsedMs={}",
                    personId,
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
                    personId,
                    expectedVersion,
                    elapsedMillis,
                    error
            );
        }
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
