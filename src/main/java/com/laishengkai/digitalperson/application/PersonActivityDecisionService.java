package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionModel;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Settles state, asks the activity model for a pure plan, validates and applies it,
 * evaluates newly started events, and saves the aggregate once with optimistic locking.
 *
 * <p>All decision sources are serialized per person. Dialogue-triggered, scheduled, command-refresh,
 * and future callers therefore cannot run overlapping model decisions against the same aggregate
 * version. Different people remain independent.</p>
 */
public final class PersonActivityDecisionService {
    public static final int MAX_OBSERVATION_LENGTH = 4_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PersonActivityDecisionService.class
    );

    private final PersonRepository personRepository;
    private final PersonStateEvolutionCoordinator stateEvolution;
    private final PersonActivityDecisionModelRunner modelRunner;
    private final PersonActivityPlanApplier planApplier;
    private final Clock clock;
    private final ConcurrentMap<PersonId, CompletableFuture<Void>> decisionQueues =
            new ConcurrentHashMap<>();

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
        this.modelRunner = new PersonActivityDecisionModelRunner(
                activityContextAssembler,
                activityDecisionModel
        );
        this.planApplier = new PersonActivityPlanApplier();
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
        ActivityDecisionDeadline decisionDeadline = ActivityDecisionDeadline.require(
                deadline,
                now,
                clock
        );
        String normalizedObservation = normalizeObservation(observation);
        return enqueueDecision(
                requestedPersonId,
                () -> decideNow(
                        requestedPersonId,
                        normalizedObservation,
                        now,
                        decisionDeadline
                )
        );
    }

    private CompletionStage<PersonActivityDecisionResult> decideNow(
            PersonId requestedPersonId,
            String normalizedObservation,
            Instant now,
            ActivityDecisionDeadline decisionDeadline
    ) {
        long startedAtNanos = System.nanoTime();
        Consumer<String> checkpoint = decisionDeadline::checkpoint;

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
                    decisionDeadline.value()
            );

            return decisionDeadline.guard(
                    stateEvolution.completePending(
                            person,
                            decisionState,
                            preparation,
                            now,
                            checkpoint
                    ),
                    "pending event evaluation"
            ).thenCompose(settledContext -> {
                checkpoint.accept("activity context assembly");
                return modelRunner.decide(
                        person,
                        decisionState,
                        settledContext,
                        normalizedObservation,
                        now,
                        decisionDeadline
                ).thenCompose(plan -> {
                    checkpoint.accept("activity plan application");
                    return applyAndEvaluate(
                            person,
                            decisionState,
                            settledContext,
                            plan,
                            now,
                            decisionDeadline,
                            checkpoint
                    );
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
                        applied.appliedPlan().plan(),
                        applied.appliedPlan().startedEvents(),
                        applied.appliedPlan().finishedEvents(),
                        person.getStateSnapshot(),
                        applied.completedContext(),
                        now,
                        now.plus(Duration.ofMinutes(
                                applied.appliedPlan().plan().nextReviewMinutes()
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

    private CompletionStage<PersonActivityDecisionResult> enqueueDecision(
            PersonId personId,
            Supplier<CompletionStage<PersonActivityDecisionResult>> decision
    ) {
        CompletableFuture<PersonActivityDecisionResult> result = new CompletableFuture<>();
        CompletableFuture<Void> scheduled = decisionQueues.compute(personId, (ignored, previous) -> {
            CompletionStage<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((value, failure) -> null);
            return ready.thenCompose(ignoredReady -> {
                final CompletionStage<PersonActivityDecisionResult> stage;
                try {
                    stage = Objects.requireNonNull(
                            decision.get(),
                            "activity decision stage cannot be null"
                    );
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                    return CompletableFuture.completedFuture(null);
                }
                return stage.handle((value, failure) -> {
                    if (failure == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(failure);
                    }
                    return null;
                });
            }).toCompletableFuture();
        });
        scheduled.whenComplete((ignored, failure) -> {
            decisionQueues.remove(personId, scheduled);
            if (failure != null && !result.isDone()) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private CompletionStage<CompletedActivityDecision> applyAndEvaluate(
            Person person,
            PersonStateSnapshot evaluationState,
            StateEvolutionContext settledContext,
            PersonActivityDecisionPlan plan,
            Instant now,
            ActivityDecisionDeadline deadline,
            Consumer<String> checkpoint
    ) {
        AppliedActivityPlan appliedPlan = planApplier.apply(
                person,
                plan,
                now,
                checkpoint
        );
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
                        appliedPlan.startedEvents(),
                        now,
                        checkpoint
                );
        return deadline.guard(
                evaluationStage,
                "new event effect evaluation"
        ).thenApply(completedContext -> new CompletedActivityDecision(
                appliedPlan,
                completedContext
        ));
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

    private record CompletedActivityDecision(
            AppliedActivityPlan appliedPlan,
            StateEvolutionContext completedContext
    ) {
        private CompletedActivityDecision {
            appliedPlan = Objects.requireNonNull(
                    appliedPlan,
                    "appliedPlan cannot be null"
            );
            completedContext = Objects.requireNonNull(
                    completedContext,
                    "completedContext cannot be null"
            );
        }
    }
}
