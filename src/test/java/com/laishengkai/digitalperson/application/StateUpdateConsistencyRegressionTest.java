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
import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PhysicalState;
import com.laishengkai.digitalperson.state.SocialState;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateTransition;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static com.laishengkai.digitalperson.support.StateEffectTestFixtures.eventBoundImpact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for state consistency across production event boundaries. */
class StateUpdateConsistencyRegressionTest {
    private static final double EPSILON = 1.0e-12;
    private static final Instant START = Instant.parse("2026-07-22T08:00:00Z");
    private static final Personality PERSONALITY = new Personality(
            0.5, 0.5, 0.5, 0.5, 0.5, 0.5
    );

    @Test
    void completedEventEffectMustStopAtTheEventEndBoundary() {
        Person person = new Person(PERSONALITY, stateWithValence(0.7));
        PersonEvent eating = event(ActivityType.EAT, "吃饭", START);

        InMemoryRepository repository = new InMemoryRepository(person);
        EventStateImpactEvaluator evaluator = context -> CompletableFuture.completedFuture(
                eventBoundImpact(new StateTransition(StateDimension.VALENCE, -1.0))
        );
        PersonEventCommandService service = new PersonEventCommandService(
                repository,
                new StateUpdater(),
                evaluator
        );

        service.start(person.getId(), eating, START).toCompletableFuture().join();

        Instant eventEnd = START.plusSeconds(600);
        service.finish(
                person.getId(),
                eating.getId(),
                EventEndReason.COMPLETED,
                eventEnd
        ).toCompletableFuture().join();

        double expectedAfterTenMinutes = 0.7 * Math.exp(-1.0 / 6.0);
        Person stored = repository.current(person.getId());
        assertEquals(
                expectedAfterTenMinutes,
                stored.getStateSnapshot().valence(),
                EPSILON,
                "an event-bound effect must not continue after the event has ended"
        );
        assertTrue(stored.getStateEvolutionContext().effects().isEmpty());
    }

    @Test
    void staleEvaluationMustNotOverwriteAReplacementEventCommittedLater() {
        Person person = new Person(PERSONALITY);
        PersonEvent eating = event(ActivityType.EAT, "吃饭", START);

        CompletableFuture<EventStateImpact> eatingEvaluation =
                new CompletableFuture<>();
        CompletableFuture<EventStateImpact> restEvaluation =
                new CompletableFuture<>();

        InMemoryRepository repository = new InMemoryRepository(person);
        EventStateImpactEvaluator evaluator = context -> switch (
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
        PersonEventCommandService service = new PersonEventCommandService(
                repository,
                new StateUpdater(),
                evaluator
        );

        CompletionStage<PersonEventCommandResult> staleStart = service.start(
                person.getId(),
                eating,
                START
        );

        Instant replacementTime = START.plusSeconds(1);
        PersonEvent resting = event(ActivityType.REST, "休息", replacementTime);
        CompletionStage<PersonEventCommandResult> newerStart = service.start(
                person.getId(),
                resting,
                replacementTime
        );
        restEvaluation.complete(
                eventBoundImpact(new StateTransition(StateDimension.ENERGY, 1.0))
        );
        newerStart.toCompletableFuture().join();

        assertEquals(resting.getId(), onlySourceEventId(
                repository.current(person.getId())
        ));

        eatingEvaluation.complete(
                eventBoundImpact(new StateTransition(StateDimension.HUNGER, -1.0))
        );
        CompletionException conflict = assertThrows(
                CompletionException.class,
                () -> staleStart.toCompletableFuture().join()
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

    private static PersonState stateWithValence(double valence) {
        return new PersonState(
                new AffectState(valence, 0.5, 0.0),
                CognitiveState.baseline(),
                PhysicalState.baseline(),
                SocialState.baseline()
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
