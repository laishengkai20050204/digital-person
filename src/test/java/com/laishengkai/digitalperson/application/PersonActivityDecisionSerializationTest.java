package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionModel;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PersonActivityDecisionSerializationTest {
    private static final Instant START = Instant.parse("2026-08-11T09:00:00Z");
    private static final Personality PERSONALITY = new Personality(
            0.6, 0.7, 0.4, 0.8, 0.6, 0.8
    );

    @Test
    void serializesConcurrentDecisionsForSamePersonAndReloadsLatestVersion() {
        Person person = Person.create(PERSONALITY);
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        CompletableFuture<PersonActivityDecisionPlan> firstPlan = new CompletableFuture<>();
        AtomicInteger modelInvocations = new AtomicInteger();
        PersonActivityDecisionModel model = context -> {
            if (modelInvocations.incrementAndGet() == 1) {
                return firstPlan;
            }
            return CompletableFuture.completedFuture(
                    PersonActivityDecisionPlan.unchanged(30)
            );
        };
        PersonActivityDecisionService service = new PersonActivityDecisionService(
                repository,
                new StateUpdater(),
                model,
                context -> CompletableFuture.completedFuture(EventStateImpact.none())
        );

        CompletableFuture<PersonActivityDecisionResult> first = service.decide(
                person.getId(),
                "dialogue evidence",
                START
        ).toCompletableFuture();
        CompletableFuture<PersonActivityDecisionResult> second = service.decide(
                person.getId(),
                START.plusSeconds(10)
        ).toCompletableFuture();

        assertEquals(1, modelInvocations.get());
        assertFalse(second.isDone());

        firstPlan.complete(PersonActivityDecisionPlan.unchanged(30));
        first.join();
        second.join();

        assertEquals(2, modelInvocations.get());
        assertEquals(2, repository.saveCount());
        assertEquals(2L, repository.currentVersion(person.getId()));
    }

    private static final class VersionedInMemoryRepository implements PersonRepository {
        private final Map<PersonId, StoredPerson> people = new HashMap<>();
        private int saveCount;

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
            saveCount++;
            people.put(
                    person.getId(),
                    new StoredPerson(person.copy(), expectedVersion + 1)
            );
            return true;
        }

        private synchronized int saveCount() {
            return saveCount;
        }

        private synchronized long currentVersion(PersonId personId) {
            return people.get(personId).version;
        }

        private record StoredPerson(Person person, long version) {
        }
    }
}
