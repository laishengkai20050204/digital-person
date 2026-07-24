package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Shared state-evolution workflow used by state updates, event commands and
 * autonomous activity decisions.
 *
 * <p>The coordinator owns only state settlement and event-effect evaluation.
 * Application services continue to own event semantics, aggregate persistence,
 * optimistic locking and use-case-specific failure policy.</p>
 */
public final class PersonStateEvolutionCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            PersonStateEvolutionCoordinator.class
    );
    private static final Consumer<String> NO_CHECKPOINT = ignored -> {
    };

    private final StateUpdater stateUpdater;
    private final EventStateImpactEvaluator evaluator;
    private final StateEvaluationContextAssembler contextAssembler;

    public PersonStateEvolutionCoordinator(
            StateUpdater stateUpdater,
            EventStateImpactEvaluator evaluator,
            StateEvaluationContextAssembler contextAssembler
    ) {
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

    public StateUpdatePreparation prepare(
            Person person,
            PersonState workingState,
            Instant evaluationTime
    ) {
        return prepare(person, workingState, evaluationTime, NO_CHECKPOINT);
    }

    public StateUpdatePreparation prepare(
            Person person,
            PersonState workingState,
            Instant evaluationTime,
            Consumer<String> checkpoint
    ) {
        Person safePerson = Objects.requireNonNull(person, "person cannot be null");
        PersonState safeState = Objects.requireNonNull(
                workingState,
                "workingState cannot be null"
        );
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        Consumer<String> guard = requireCheckpoint(checkpoint);
        guard.accept("state preparation");
        return stateUpdater.prepareWithNaturalEvolution(
                safePerson.getId(),
                safePerson.getIdentity().timeZone(),
                safeState,
                safePerson.getCurrentPersonEvents(now),
                safePerson.getPersonTimeline().getAll(),
                now,
                safePerson.getStateEvolutionContext(),
                eventEndTimes(safePerson)
        );
    }

    public CompletionStage<StateEvolutionContext> completePending(
            Person person,
            PersonStateSnapshot state,
            StateUpdatePreparation preparation,
            Instant evaluationTime
    ) {
        return completePending(
                person,
                state,
                preparation,
                evaluationTime,
                NO_CHECKPOINT
        );
    }

    public CompletionStage<StateEvolutionContext> completePending(
            Person person,
            PersonStateSnapshot state,
            StateUpdatePreparation preparation,
            Instant evaluationTime,
            Consumer<String> checkpoint
    ) {
        Person safePerson = Objects.requireNonNull(person, "person cannot be null");
        PersonStateSnapshot safeState = Objects.requireNonNull(
                state,
                "state cannot be null"
        );
        StateUpdatePreparation safePreparation = Objects.requireNonNull(
                preparation,
                "preparation cannot be null"
        );
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        Consumer<String> guard = requireCheckpoint(checkpoint);

        final List<CompletableFuture<EventEffectRegistration>> evaluations;
        try {
            guard.accept("pending event evaluation");
            evaluations = safePreparation.eventsToEvaluate().stream()
                    .map(event -> evaluateEvent(
                            safePerson,
                            safeState,
                            safePreparation.settledContext(),
                            event,
                            now,
                            guard
                    ))
                    .map(CompletionStage::toCompletableFuture)
                    .toList();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }

        if (evaluations.isEmpty()) {
            return CompletableFuture.completedFuture(
                    safePreparation.settledContext()
            );
        }

        LOGGER.info(
                "Automatically evaluating pending person events: personId={}, pendingEventIds={}",
                safePerson.getId(),
                safePreparation.eventsToEvaluate().stream()
                        .map(PersonEvent::getId)
                        .toList()
        );
        return CompletableFuture.allOf(
                evaluations.toArray(CompletableFuture[]::new)
        ).thenApply(ignored -> {
            guard.accept("pending event effect completion");
            return stateUpdater.complete(
                    safePreparation,
                    evaluations.stream()
                            .map(CompletableFuture::join)
                            .toList()
            );
        });
    }

    public StateEvolutionContext afterTimelineChange(
            Person person,
            StateEvolutionContext settledContext,
            Instant evaluationTime
    ) {
        return afterTimelineChange(
                person,
                settledContext,
                evaluationTime,
                NO_CHECKPOINT
        );
    }

    public StateEvolutionContext afterTimelineChange(
            Person person,
            StateEvolutionContext settledContext,
            Instant evaluationTime,
            Consumer<String> checkpoint
    ) {
        Person safePerson = Objects.requireNonNull(person, "person cannot be null");
        StateEvolutionContext safeContext = Objects.requireNonNull(
                settledContext,
                "settledContext cannot be null"
        );
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        Consumer<String> guard = requireCheckpoint(checkpoint);
        guard.accept("timeline settlement");
        return stateUpdater.afterTimelineChange(
                safeContext,
                safePerson.getCurrentPersonEvents(now),
                now,
                eventEndTimes(safePerson)
        );
    }

    public CompletionStage<StateEvolutionContext> evaluateStartedEvents(
            Person person,
            PersonStateSnapshot state,
            StateEvolutionContext baseContext,
            List<PersonEvent> startedEvents,
            Instant evaluationTime
    ) {
        return evaluateStartedEvents(
                person,
                state,
                baseContext,
                startedEvents,
                evaluationTime,
                NO_CHECKPOINT
        );
    }

    public CompletionStage<StateEvolutionContext> evaluateStartedEvents(
            Person person,
            PersonStateSnapshot state,
            StateEvolutionContext baseContext,
            List<PersonEvent> startedEvents,
            Instant evaluationTime,
            Consumer<String> checkpoint
    ) {
        Person safePerson = Objects.requireNonNull(person, "person cannot be null");
        PersonStateSnapshot safeState = Objects.requireNonNull(
                state,
                "state cannot be null"
        );
        StateEvolutionContext safeContext = Objects.requireNonNull(
                baseContext,
                "baseContext cannot be null"
        );
        List<PersonEvent> events = List.copyOf(Objects.requireNonNull(
                startedEvents,
                "startedEvents cannot be null"
        ));
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        Consumer<String> guard = requireCheckpoint(checkpoint);
        if (events.isEmpty()) {
            return CompletableFuture.completedFuture(safeContext);
        }

        try {
            Map<ActivityChannel, PersonEvent> pendingEvents = new EnumMap<>(
                    ActivityChannel.class
            );
            for (PersonEvent event : events) {
                PersonEvent previous = pendingEvents.put(event.getChannel(), event);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "startedEvents cannot contain duplicate channels: "
                                    + event.getChannel()
                    );
                }
            }
            StateUpdatePreparation preparation = new StateUpdatePreparation(
                    safeContext,
                    pendingEvents
            );
            List<CompletableFuture<EventEffectRegistration>> evaluations = events.stream()
                    .map(event -> evaluateEvent(
                            safePerson,
                            safeState,
                            safeContext,
                            event,
                            now,
                            guard
                    ))
                    .map(CompletionStage::toCompletableFuture)
                    .toList();

            return CompletableFuture.allOf(
                    evaluations.toArray(CompletableFuture[]::new)
            ).thenApply(ignored -> {
                guard.accept("new event effect completion");
                return stateUpdater.complete(
                        preparation,
                        evaluations.stream()
                                .map(CompletableFuture::join)
                                .toList()
                );
            });
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletionStage<EventEffectRegistration> evaluateEvent(
            Person person,
            PersonStateSnapshot state,
            StateEvolutionContext evolution,
            PersonEvent event,
            Instant evaluationTime,
            Consumer<String> checkpoint
    ) {
        PersonEvent safeEvent = Objects.requireNonNull(event, "event cannot be null");
        checkpoint.accept("state effect context assembly");
        CompletionStage<StateEvaluationContext> contextStage = Objects.requireNonNull(
                contextAssembler.assemble(
                        person,
                        state,
                        evolution,
                        safeEvent.copy(),
                        evaluationTime
                ),
                "contextAssembler stage cannot be null"
        );
        return contextStage.thenCompose(context -> {
            checkpoint.accept("state effect model invocation");
            return Objects.requireNonNull(
                    evaluator.evaluate(Objects.requireNonNull(
                            context,
                            "assembled context cannot be null"
                    )),
                    "evaluator stage cannot be null"
            );
        }).thenApply(impact -> {
            checkpoint.accept("state effect model response");
            return toRegistration(safeEvent, evaluationTime, impact);
        });
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

    private static Map<EventId, Instant> eventEndTimes(Person person) {
        Map<EventId, Instant> endTimes = new HashMap<>();
        person.getPersonTimeline().getAll().forEach(event ->
                event.getEndTime().ifPresent(endTime ->
                        endTimes.put(event.getId(), endTime)
                )
        );
        return Map.copyOf(endTimes);
    }

    private static Consumer<String> requireCheckpoint(Consumer<String> checkpoint) {
        return Objects.requireNonNull(checkpoint, "checkpoint cannot be null");
    }

}
