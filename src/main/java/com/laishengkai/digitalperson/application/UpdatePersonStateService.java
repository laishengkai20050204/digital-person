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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class UpdatePersonStateService {
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

    public CompletionStage<StateUpdateResult> update(PersonId personId, Instant now) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        Instant currentTime = Objects.requireNonNull(now, "now cannot be null");
        Person person = personRepository.findById(requestedPersonId)
                .orElseThrow(() -> new PersonNotFoundException(requestedPersonId));

        PersonState workingState = person.getState();
        StateUpdatePreparation preparation = stateUpdater.prepare(
                workingState,
                person.getCurrentPersonEvents(currentTime),
                currentTime,
                person.getStateEvolutionContext()
        );
        PersonStateSnapshot evaluationSnapshot = workingState.snapshot();

        List<CompletableFuture<ChannelStateEffect>> evaluations =
                preparation.eventsToEvaluate().stream()
                        .map(event -> evaluate(evaluationSnapshot, event))
                        .map(CompletionStage::toCompletableFuture)
                        .toList();

        CompletableFuture<Void> allEvaluations = CompletableFuture.allOf(
                evaluations.toArray(CompletableFuture[]::new)
        );

        return allEvaluations.thenApply(ignored -> {
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
    }

    private CompletionStage<ChannelStateEffect> evaluate(
            PersonStateSnapshot state,
            PersonEvent event
    ) {
        CompletionStage<List<StateTransition>> result = Objects.requireNonNull(
                evaluator.evaluate(state, event.copy()),
                "evaluator stage cannot be null"
        );

        return result.thenApply(transitions -> new ChannelStateEffect(
                event.getChannel(),
                event.getId(),
                List.copyOf(
                        Objects.requireNonNull(
                                transitions,
                                "evaluator result cannot be null"
                        )
                )
        ));
    }
}
