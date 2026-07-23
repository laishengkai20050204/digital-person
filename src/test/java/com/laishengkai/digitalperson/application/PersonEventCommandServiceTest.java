package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
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
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonEventCommandServiceTest {
    private static final double EPSILON = 1.0e-12;
    private static final Instant START = Instant.parse("2026-07-22T08:00:00Z");
    private static final Personality PERSONALITY = new Personality(
            0.5, 0.5, 0.5, 0.5, 0.5, 0.5
    );

    @Test
    void replacementSettlesOldEffectAndInstallsNewEffectAtomically() {
        Person person = new Person(PERSONALITY, stateWithHunger(0.7));
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        PersonEventCommandService service = service(
                repository,
                context -> switch (context.newEvent().activityType()) {
                    case "EAT" -> CompletableFuture.completedFuture(
                            List.of(new StateTransition(StateDimension.HUNGER, -1.0))
                    );
                    case "REST" -> CompletableFuture.completedFuture(
                            List.of(new StateTransition(StateDimension.ENERGY, 1.0))
                    );
                    default -> CompletableFuture.failedFuture(
                            new IllegalArgumentException("unexpected activity")
                    );
                }
        );

        PersonEvent eating = openEvent(ActivityType.EAT, "吃饭", START);
        service.start(person.getId(), eating, START).toCompletableFuture().join();

        Instant replacementTime = START.plusSeconds(600);
        PersonEvent resting = openEvent(ActivityType.REST, "休息", replacementTime);
        PersonEventCommandResult result = service.start(
                person.getId(),
                resting,
                replacementTime
        ).toCompletableFuture().join();

        double expectedHunger = 0.7 * Math.exp(-1.0 / 6.0);
        assertEquals(expectedHunger, result.state().hunger(), EPSILON);
        assertEquals(1, result.stateEvolutionContext().effects().size());
        assertTrue(result.stateEvolutionContext().effects().values().stream()
                .allMatch(effect -> effect.sourceEventId().equals(resting.getId())));
        assertTrue(result.stateEvolutionContext().evaluatedEventIds().contains(resting.getId()));
        assertFalse(result.stateEvolutionContext().evaluatedEventIds().contains(eating.getId()));

        VersionedPerson stored = repository.current(person.getId());
        PersonEvent replaced = stored.person().getPersonEventById(eating.getId()).orElseThrow();
        assertEquals(EventEndReason.REPLACED, replaced.getEndReason().orElseThrow());
        assertEquals(replacementTime, replaced.getEndTime().orElseThrow());
        assertEquals(2L, stored.version());
    }

    @Test
    void finishSettlesThroughEndTimeThenRemovesEventBoundEffect() {
        Person person = new Person(PERSONALITY, stateWithHunger(0.7));
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        PersonEventCommandService service = service(
                repository,
                context -> CompletableFuture.completedFuture(
                        List.of(new StateTransition(StateDimension.HUNGER, -1.0))
                )
        );
        PersonEvent eating = openEvent(ActivityType.EAT, "吃饭", START);
        service.start(person.getId(), eating, START).toCompletableFuture().join();

        Instant finishTime = START.plusSeconds(600);
        PersonEventCommandResult result = service.finish(
                person.getId(),
                eating.getId(),
                EventEndReason.COMPLETED,
                finishTime
        ).toCompletableFuture().join();

        double expectedHunger = 0.7 * Math.exp(-1.0 / 6.0);
        assertEquals(expectedHunger, result.state().hunger(), EPSILON);
        assertTrue(result.stateEvolutionContext().effects().isEmpty());
        assertTrue(result.stateEvolutionContext().evaluatedEventIds().isEmpty());
        assertEquals(EventEndReason.COMPLETED, result.event().getEndReason().orElseThrow());
        assertEquals(finishTime, result.event().getEndTime().orElseThrow());
        assertEquals(2L, repository.current(person.getId()).version());
    }

    @Test
    void historicalRecordDoesNotReplayStateOrInvokeEvaluator() {
        Person person = new Person(PERSONALITY, stateWithHunger(0.7));
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        AtomicInteger evaluationCount = new AtomicInteger();
        PersonEventCommandService service = service(repository, context -> {
            evaluationCount.incrementAndGet();
            return CompletableFuture.completedFuture(
                    List.of(new StateTransition(StateDimension.HUNGER, -1.0))
            );
        });
        PersonEvent historical = new PersonEvent(
                ActivityType.EAT,
                "昨晚吃饭",
                "",
                TimeRange.closed(
                        START.minusSeconds(3600),
                        START.minusSeconds(3000)
                )
        );

        PersonEventCommandResult result = service.recordHistorical(
                person.getId(),
                historical,
                START
        ).toCompletableFuture().join();

        assertEquals(0.7, result.state().hunger(), EPSILON);
        assertTrue(result.stateEvolutionContext().effects().isEmpty());
        assertTrue(result.stateEvolutionContext().previousUpdateTime().isEmpty());
        assertEquals(0, evaluationCount.get());
        assertEquals(EventEndReason.COMPLETED, result.event().getEndReason().orElseThrow());
        assertEquals(1L, repository.current(person.getId()).version());
    }

    @Test
    void staleStartEvaluationCannotCommitAfterConcurrentVersionChange() {
        Person person = new Person(PERSONALITY);
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        CompletableFuture<List<StateTransition>> evaluation = new CompletableFuture<>();
        PersonEventCommandService service = service(repository, context -> evaluation);
        PersonEvent eating = openEvent(ActivityType.EAT, "吃饭", START);

        CompletableFuture<PersonEventCommandResult> pending = service.start(
                person.getId(),
                eating,
                START
        ).toCompletableFuture();
        repository.advanceVersion(person.getId());
        evaluation.complete(
                List.of(new StateTransition(StateDimension.HUNGER, -1.0))
        );

        CompletionException error = assertThrows(CompletionException.class, pending::join);
        assertInstanceOf(PersonVersionConflictException.class, error.getCause());

        VersionedPerson stored = repository.current(person.getId());
        assertEquals(1L, stored.version());
        assertTrue(stored.person().getPersonEventById(eating.getId()).isEmpty());
        assertTrue(stored.person().getStateEvolutionContext().effects().isEmpty());
    }

    @Test
    void realtimeStartRejectsPastStartTime() {
        Person person = new Person(PERSONALITY);
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        PersonEventCommandService service = service(
                repository,
                context -> CompletableFuture.completedFuture(List.of())
        );
        PersonEvent pastEvent = openEvent(
                ActivityType.REST,
                "较早开始的休息",
                START.minusSeconds(60)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.start(person.getId(), pastEvent, START)
        );
        assertEquals(0L, repository.current(person.getId()).version());
    }

    @Test
    void realtimeCommandRejectsAnActiveEventThatWasNeverEvaluated() {
        Person person = new Person(PERSONALITY);
        PersonEvent untracked = openEvent(ActivityType.EAT, "绕过服务的事件", START);
        person.startPersonEvent(untracked, START);
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        PersonEventCommandService service = service(
                repository,
                context -> CompletableFuture.completedFuture(List.of())
        );
        Instant replacementTime = START.plusSeconds(1);
        PersonEvent replacement = openEvent(
                ActivityType.REST,
                "新事件",
                replacementTime
        );

        CompletionException error = assertThrows(
                CompletionException.class,
                () -> service.start(person.getId(), replacement, replacementTime)
                        .toCompletableFuture()
                        .join()
        );
        assertInstanceOf(UnsettledPersonEventException.class, error.getCause());
        assertEquals(0L, repository.current(person.getId()).version());
    }

    private static PersonEventCommandService service(
            PersonRepository repository,
            com.laishengkai.digitalperson.state.StateTransitionEvaluator evaluator
    ) {
        return new PersonEventCommandService(
                repository,
                new StateUpdater(),
                evaluator
        );
    }

    private static PersonEvent openEvent(
            ActivityType type,
            String title,
            Instant startTime
    ) {
        return new PersonEvent(type, title, "", TimeRange.openEnded(startTime));
    }

    private static PersonState stateWithHunger(double hunger) {
        return new PersonState(
                new AffectState(0.0, 0.5, 0.0),
                CognitiveState.baseline(),
                new PhysicalState(0.0, 0.0, hunger),
                SocialState.baseline()
        );
    }

    private static final class VersionedInMemoryRepository implements PersonRepository {
        private final Map<PersonId, StoredPerson> people = new HashMap<>();

        private VersionedInMemoryRepository(Person person) {
            people.put(person.getId(), new StoredPerson(person.copy(), 0L));
        }

        @Override
        public synchronized Optional<VersionedPerson> findById(PersonId personId) {
            StoredPerson stored = people.get(personId);
            if (stored == null) {
                return Optional.empty();
            }
            return Optional.of(new VersionedPerson(stored.person.copy(), stored.version));
        }

        @Override
        public synchronized boolean save(Person person, long expectedVersion) {
            StoredPerson stored = people.get(person.getId());
            if (stored == null || stored.version != expectedVersion) {
                return false;
            }
            people.put(
                    person.getId(),
                    new StoredPerson(person.copy(), expectedVersion + 1)
            );
            return true;
        }

        private synchronized VersionedPerson current(PersonId personId) {
            StoredPerson stored = people.get(personId);
            return new VersionedPerson(stored.person.copy(), stored.version);
        }

        private synchronized void advanceVersion(PersonId personId) {
            StoredPerson stored = people.get(personId);
            people.put(
                    personId,
                    new StoredPerson(stored.person.copy(), stored.version + 1)
            );
        }

        private static final class StoredPerson {
            private final Person person;
            private final long version;

            private StoredPerson(Person person, long version) {
                this.person = person;
                this.version = version;
            }
        }
    }
}
