package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.personality.Personality;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdatePersonStateServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");

    @Test
    void savesOnlyAfterAllEvaluationsSucceed() {
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

        UpdatePersonStateService service = new UpdatePersonStateService(
                repository,
                new StateUpdater(),
                context -> CompletableFuture.completedFuture(
                        List.of(new StateTransition(StateDimension.HUNGER, -1.0))
                )
        );

        service.update(person.getId(), NOW).toCompletableFuture().join();

        assertEquals(1, repository.saveCount);
        assertEquals(1, person.getStateEvolutionContext().channelEffects().size());
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
        UpdatePersonStateService service = new UpdatePersonStateService(
                repository,
                new StateUpdater(),
                context -> CompletableFuture.failedFuture(
                        new IllegalStateException("LLM unavailable")
                )
        );

        assertThrows(
                RuntimeException.class,
                () -> service.update(person.getId(), NOW)
                        .toCompletableFuture()
                        .join()
        );

        assertEquals(0, repository.saveCount);
        assertEquals(0, person.getStateEvolutionContext().channelEffects().size());
    }

    private static final class InMemoryRepository implements PersonRepository {
        private final Map<PersonId, Person> people = new HashMap<>();
        private int saveCount;

        private InMemoryRepository(Person person) {
            people.put(person.getId(), person);
        }

        @Override
        public Optional<Person> findById(PersonId personId) {
            return Optional.ofNullable(people.get(personId));
        }

        @Override
        public void save(Person person) {
            people.put(person.getId(), person);
            saveCount++;
        }
    }
}
