package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.state.ChannelStateEffect;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransition;
import com.laishengkai.digitalperson.state.StateTransitionEvaluator;
import com.laishengkai.digitalperson.state.StateUpdatePreparation;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Executes one complete person-state update use case.
 *
 * <p>This application service coordinates persistence, deterministic state
 * settlement and asynchronous event evaluation. Domain calculations remain in
 * {@link StateUpdater}; model-specific work remains behind
 * {@link StateTransitionEvaluator}.</p>
 *
 * <p>Logs deliberately contain identifiers and timing information only. Event
 * titles, locations, participants, notes and conversation text must not be
 * written here because they may contain private user data.</p>
 */
public final class UpdatePersonStateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            UpdatePersonStateService.class
    );

    private final PersonRepository personRepository;
    private final StateUpdater stateUpdater;
    private final StateTransitionEvaluator evaluator;

    public UpdatePersonStateService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            StateTransitionEvaluator evaluator
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
    }

    /**
     * Updates one person's short-term state at the supplied time.
     *
     * <p>The person is modified and saved only after every asynchronous event
     * evaluation succeeds and the returned effects pass domain validation. A
     * failed evaluation therefore leaves the persisted aggregate unchanged.</p>
     *
     * @param personId person aggregate to update
     * @param now      logical update time
     * @return asynchronous state-update result
     */
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

            // Work on a defensive copy so asynchronous failures cannot partially
            // mutate the aggregate loaded from the repository.
            PersonState workingState = person.getState();
            StateUpdatePreparation preparation = stateUpdater.prepare(
                    workingState,
                    person.getCurrentPersonEvents(currentTime),
                    currentTime,
                    person.getStateEvolutionContext()
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
                            .map(event -> evaluate(evaluationSnapshot, event))
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

                // This is the commit point: nothing before it changes the loaded
                // aggregate or writes it back to persistence.
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

    /**
     * Converts an evaluator response into the channel-scoped domain effect that
     * {@link StateUpdater#complete(StateUpdatePreparation, java.util.Collection)}
     * expects.
     */
    private CompletionStage<ChannelStateEffect> evaluate(
            PersonStateSnapshot state,
            PersonEvent event
    ) {
        LOGGER.debug(
                "Evaluating event state effect: eventId={}, channel={}, activityType={}",
                event.getId(),
                event.getChannel(),
                event.getActivityType()
        );

        CompletionStage<List<StateTransition>> result = Objects.requireNonNull(
                evaluator.evaluate(state, event.copy()),
                "evaluator stage cannot be null"
        );

        return result.thenApply(transitions -> {
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

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
