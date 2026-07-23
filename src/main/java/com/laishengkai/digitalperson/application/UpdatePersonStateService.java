package com.laishengkai.digitalperson.application;

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

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Executes one complete person-state update use case. */
public final class UpdatePersonStateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            UpdatePersonStateService.class
    );

    private final PersonRepository personRepository;
    private final PersonStateEvolutionCoordinator stateEvolution;

    public UpdatePersonStateService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            EventStateImpactEvaluator evaluator
    ) {
        this(
                personRepository,
                new PersonStateEvolutionCoordinator(
                        stateUpdater,
                        evaluator,
                        DefaultStateEvaluationContextAssembler.withoutExternalSources()
                )
        );
    }

    public UpdatePersonStateService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            EventStateImpactEvaluator evaluator,
            StateEvaluationContextAssembler contextAssembler
    ) {
        this(
                personRepository,
                new PersonStateEvolutionCoordinator(
                        stateUpdater,
                        evaluator,
                        contextAssembler
                )
        );
    }

    public UpdatePersonStateService(
            PersonRepository personRepository,
            PersonStateEvolutionCoordinator stateEvolution
    ) {
        this.personRepository = Objects.requireNonNull(
                personRepository,
                "personRepository cannot be null"
        );
        this.stateEvolution = Objects.requireNonNull(
                stateEvolution,
                "stateEvolution cannot be null"
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

        VersionedPerson loadedPerson = personRepository.findById(requestedPersonId)
                .orElseThrow(() -> new PersonNotFoundException(requestedPersonId));
        Person person = loadedPerson.person().copy();
        long expectedVersion = loadedPerson.version();
        PersonState workingState = person.getState();
        StateUpdatePreparation preparation = stateEvolution.prepare(
                person,
                workingState,
                currentTime
        );
        PersonStateSnapshot evaluationSnapshot = workingState.snapshot();

        LOGGER.debug(
                "Prepared person state update: personId={}, expectedVersion={}, pendingChannels={}, retainedEffectCount={}",
                requestedPersonId,
                expectedVersion,
                preparation.pendingEvents().keySet(),
                preparation.settledContext().effects().size()
        );

        return stateEvolution.completePending(
                person,
                evaluationSnapshot,
                preparation,
                currentTime
        ).thenApply(completedContext -> {
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
        }).whenComplete((result, error) -> logCompletion(
                requestedPersonId,
                expectedVersion,
                preparation.eventsToEvaluate().size(),
                startedAtNanos,
                error
        ));
    }

    private static void logCompletion(
            PersonId personId,
            long expectedVersion,
            int evaluatedEventCount,
            long startedAtNanos,
            Throwable error
    ) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAtNanos
        );
        if (error == null) {
            LOGGER.info(
                    "Completed person state update: personId={}, expectedVersion={}, evaluatedEventCount={}, elapsedMs={}",
                    personId,
                    expectedVersion,
                    evaluatedEventCount,
                    elapsedMillis
            );
        } else {
            LOGGER.warn(
                    "Person state update failed: personId={}, expectedVersion={}, elapsedMs={}",
                    personId,
                    expectedVersion,
                    elapsedMillis,
                    error
            );
        }
    }
}
