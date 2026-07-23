package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateTransition;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.laishengkai.digitalperson.support.StateEffectTestFixtures.eventBoundImpact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdatePersonStateServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");

    @Test
    void savesOnlyAfterAllEvaluationsSucceed() {
        Person person = new Person(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        PersonEvent event = new PersonEvent(
                ActivityType.EAT,
                "吃饭",
                "",
                TimeRange.openEnded(NOW)
        );
        person.startPersonEvent(event, NOW);
        InMemoryRepository repository = new InMemoryRepository(person);

        EventStateImpactEvaluator evaluator = context -> CompletableFuture.completedFuture(
                eventBoundImpact(new StateTransition(StateDimension.HUNGER, -1.0))
        );
        UpdatePersonStateService service = new UpdatePersonStateService(
                repository,
                new StateUpdater(),
                evaluator
        );

        service.update(person.getId(), NOW).toCompletableFuture().join();

        assertEquals(1, repository.saveCount);
        assertEquals(
                1,
                repository.current(person.getId())
                        .getStateEvolutionContext()
                        .effects()
                        .size()
        );
        assertEquals(
                event.getId(),
                repository.current(person.getId())
                        .getStateEvolutionContext()
                        .effects()
                        .values()
                        .iterator()
                        .next()
                        .sourceEventId()
        );
    }

    @Test
    void failedEvaluationDoesNotCommitWorkingStateOrContext() {
        Person person = new Person(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        person.startPersonEvent(
                new PersonEvent(
                        ActivityType.EAT,
                        "吃饭",
                        "",
                        TimeRange.openEnded(NOW)
                ),
                NOW
        );
        InMemoryRepository repository = new InMemoryRepository(person);
        EventStateImpactEvaluator evaluator = context -> CompletableFuture.failedFuture(
                new IllegalStateException("LLM unavailable")
        );
        UpdatePersonStateService service = new UpdatePersonStateService(
                repository,
                new StateUpdater(),
                evaluator
        );

        assertThrows(
                RuntimeException.class,
                () -> service.update(person.getId(), NOW)
                        .toCompletableFuture()
                        .join()
        );

        assertEquals(0, repository.saveCount);
        assertEquals(
                0,
                repository.current(person.getId())
                        .getStateEvolutionContext()
                        .effects()
                        .size()
        );
    }

    private static final class InMemoryRepository implements PersonRepository {
        private final Map<PersonId, StoredPerson> people = new HashMap<>();
        private int saveCount;

        private InMemoryRepository(Person person) {
            people.put(person.getId(), new StoredPerson(person.copy(), 0));
        }

        @Override
        public Optional<VersionedPerson> findById(PersonId personId) {
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
        public boolean save(Person person, long expectedVersion) {
            StoredPerson stored = people.get(person.getId());
            if (stored == null || stored.version() != expectedVersion) {
                return false;
            }
            people.put(
                    person.getId(),
                    new StoredPerson(person.copy(), expectedVersion + 1)
            );
            saveCount++;
            return true;
        }

        private Person current(PersonId personId) {
            return people.get(personId).person().copy();
        }
    }

    private record StoredPerson(Person person, long version) {
    }
}
