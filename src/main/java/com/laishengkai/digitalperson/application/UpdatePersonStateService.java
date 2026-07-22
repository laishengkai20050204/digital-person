package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.state.ChannelStateEffect;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransition;
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
    private final StateTransitionEvaluator evaluator;
    private final StateEvaluationContextAssembler contextAssembler;

    /**
     * Compatibility constructor for callers that have not connected memory or
     * conversation providers yet.
     */
    public UpdatePersonStateService(
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

    public UpdatePersonStateService(
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
            Person person = personRepository.findById(requestedPersonId)
                    .orElseThrow(() -> {
                        LOGGER.warn(
                                "Cannot update missing person: personId={}",
                                requestedPersonId
                        );
                        return new PersonNotFoundException(requestedPersonId);
                    });

            PersonState workingState = person.getState();
            StateEvolutionContext existingContext = person.getStateEvolutionContext();
            StateUpdatePreparation preparation = stateUpdater.prepare(
                    workingState,
                    person.getCurrentPersonEvents(currentTime),
                    currentTime,
                    existingContext,
                    cachedEffectEndTimes(person, existingContext)
            );
            PersonStateSnapshot evaluationSnapshot = workingState.snapshot();

            LOGGER.debug(
                    "Prepared person state update: personId={}, pendingChannels={}, retainedEffectCount={}",
                    requestedPersonId,
                    preparation.pendingEvents().keySet(),
                    preparation.settledContext().channelEffects().size()
            );

            List<CompletableFuture<ChannelStateEffect>> evaluations =
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
                List<ChannelStateEffect> effects = new ArrayList<>(evaluations.size());
                for (CompletableFuture<ChannelStateEffect> evaluation : evaluations) {
                    effects.add(evaluation.join());
                }

                StateEvolutionContext completedContext = stateUpdater.complete(
                        preparation,
                        effects
                );

                person.commitStateUpdate(workingState, completedContext);
                personRepository.save(person);

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
                            "Completed person state update: personId={}, evaluatedEventCount={}, elapsedMs={}",
                            requestedPersonId,
                            evaluations.size(),
                            elapsedMillis
                    );
                } else {
                    LOGGER.warn(
                            "Person state update failed: personId={}, elapsedMs={}",
                            requestedPersonId,
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

    private CompletionStage<ChannelStateEffect> evaluate(
            Person person,
            PersonStateSnapshot state,
            PersonEvent event,
            Instant evaluationTime
    ) {
        LOGGER.debug(
                "Evaluating event state effect: eventId={}, channel={}, activityType={}",
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
                .thenApply(transitions -> {
                    List<StateTransition> safeTransitions = List.copyOf(
                            Objects.requireNonNull(
                                    transitions,
                                    "evaluator result cannot be null"
                            )
                    );

                    LOGGER.debug(
                            "Evaluated event state effect: eventId={}, channel={}, transitionCount={}",
                            event.getId(),
                            event.getChannel(),
                            safeTransitions.size()
                    );

                    return new ChannelStateEffect(
                            event.getChannel(),
                            event.getId(),
                            safeTransitions
                    );
                });
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

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
