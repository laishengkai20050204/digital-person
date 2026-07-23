package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.AffectState;
import com.laishengkai.digitalperson.state.CognitiveState;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PhysicalState;
import com.laishengkai.digitalperson.state.SocialState;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateTransition;
import com.laishengkai.digitalperson.state.StateTransitionEvaluator;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for state consistency across event and asynchronous boundaries. */
class StateUpdateConsistencyRegressionTest {
    private static final double EPSILON = 1.0e-12;
    private static final Instant START = Instant.parse("2026-07-22T08:00:00Z");
    private static final Personality PERSONALITY = new Personality(
            0.5, 0.5, 0.5, 0.5, 0.5, 0.5
    );

    @Test
    void completedEventEffectMustStopAtTheEventEndBoundary() {
        Person person = new Person(PERSONALITY, stateWithHunger(0.7));
        PersonEvent eating = event(ActivityType.EAT, "吃饭", START);
        person.startPersonEvent(eating, START);

        InMemoryRepository repository = new InMemoryRepository(person);
        StateTransitionEvaluator evaluator = context -> CompletableFuture.completedFuture(
                List.of(new StateTransition(StateDimension.HUNGER, -1.0))
        );
        UpdatePersonStateService service = new UpdatePersonStateService(
                repository,
                new StateUpdater(),
                evaluator
        );

        service.update(person.getId(), START).toCompletableFuture().join();

        Instant eventEnd = START.plusSeconds(600);
        VersionedPerson loaded = repository.findById(person.getId()).orElseThrow();
        Person endedEventPerson = loaded.person().copy();
        endedEventPerson.finishPersonEvent(
                eating.getId(),
                eventEnd,
                EventEndReason.COMPLETED,
                eventEnd
        );
        assertTrue(repository.save(endedEventPerson, loaded.version()));

        service.update(person.getId(), START.plusSeconds(1800))
                .toCompletableFuture()
                .join();

        double expectedAfterTenMinutes = 0.7 * Math.exp(-1.0 / 6.0);
        Person stored = repository.current(person.getId());
        assertEquals(
                expectedAfterTenMinutes,
                stored.getState().getPhysicalState().getHunger(),
                EPSILON,
                "an event-bound effect must not continue after the event has ended"
        );
        assertTrue(stored.getStateEvolutionContext().effects().isEmpty());
    }

    @Test
    void staleEvaluationMustNotOverwriteAReplacementEventCommittedLater() {
        Person person = new Person(PERSONALITY);
        PersonEvent eating = event(ActivityType.EAT, "吃饭", START);
        person.startPersonEvent(eating, START);

        CompletableFuture<List<StateTransition>> eatingEvaluation =
                new CompletableFuture<>();
        CompletableFuture<List<StateTransition>> restEvaluation =
                new CompletableFuture<>();

        InMemoryRepository repository = new InMemoryRepository(person);
        StateTransitionEvaluator evaluator = context -> switch (
                context.newEvent().activityType()
        ) {
            case "EAT" -> eatingEvaluation;
            case "REST" -> restEvaluation;
            default -> CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "unexpected activity type: "
                                    + context.newEvent().activityType()
                    )
            );
        };
        UpdatePersonStateService service = new UpdatePersonStateService(
                repository,
                new StateUpdater(),
                evaluator
        );

        CompletionStage<StateUpdateResult> staleUpdate = service.update(
                person.getId(),
                START
        );

        Instant replacementTime = START.plusSeconds(1);
        PersonEvent resting = event(ActivityType.REST, "休息", replacementTime);
        VersionedPerson beforeReplacement = repository.findById(person.getId())
                .orElseThrow();
        Person replacementPerson = beforeReplacement.person().copy();
        replacementPerson.startPersonEvent(resting, replacementTime);
        assertTrue(repository.save(replacementPerson, beforeReplacement.version()));

        CompletionStage<StateUpdateResult> newerUpdate = service.update(
                person.getId(),
                replacementTime
        );
        restEvaluation.complete(
                List.of(new StateTransition(StateDimension.ENERGY, 1.0))
        );
        newerUpdate.toCompletableFuture().join();

        assertEquals(resting.getId(), onlySourceEventId(
                repository.current(person.getId())
        ));

        eatingEvaluation.complete(
                List.of(new StateTransition(StateDimension.HUNGER, -1.0))
        );
        CompletionException conflict = assertThrows(
                CompletionException.class,
                () -> staleUpdate.toCompletableFuture().join()
        );
        assertInstanceOf(PersonVersionConflictException.class, conflict.getCause());

        assertEquals(
                resting.getId(),
                onlySourceEventId(repository.current(person.getId())),
                "an older asynchronous evaluation must not overwrite newer state"
        );
    }

    private static EventId onlySourceEventId(Person person) {
        assertEquals(1, person.getStateEvolutionContext().effects().size());
        return person.getStateEvolutionContext().effects().values()
                .iterator()
                .next()
                .sourceEventId();
    }

    private static PersonEvent event(
            ActivityType activityType,
            String title,
            Instant startTime
    ) {
        return new PersonEvent(
                activityType,
                title,
                "",
                TimeRange.openEnded(startTime)
        );
    }

    private static PersonState stateWithHunger(double hunger) {
        return new PersonState(
                new AffectState(0.0, 0.5, 0.0),
                CognitiveState.baseline(),
                new PhysicalState(0.0, 0.0, hunger),
                SocialState.baseline()
        );
    }

    private static final class InMemoryRepository implements PersonRepository {
        private final Map<PersonId, StoredPerson> people = new HashMap<>();

        private InMemoryRepository(Person person) {
            people.put(person.getId(), new StoredPerson(person.copy(), 0));
        }

        @Override
        public synchronized Optional<VersionedPerson> findById(PersonId personId) {
            StoredPerson stored = people.get(personId);
            if (stored == null) {
                return Optional.empty();
            }
            return Optional.of(new VersionedPerson(
                    stored.person().copy(),
                    stored.version()
            ));
        }

        @Override
        public synchronized boolean save(Person person, long expectedVersion) {
            StoredPerson stored = people.get(person.getId());
            if (stored == null || stored.version() != expectedVersion) {
                return false;
            }
            people.put(
                    person.getId(),
                    new StoredPerson(person.copy(), expectedVersion + 1)
            );
            return true;
        }

        private synchronized Person current(PersonId personId) {
            return people.get(personId).person().copy();
        }
    }

    private record StoredPerson(Person person, long version) {
    }
}
