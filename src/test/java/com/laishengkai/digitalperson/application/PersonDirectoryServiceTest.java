package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonCreationRepository;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentity;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonDirectoryServiceTest {
    private static final Personality PERSONALITY = new Personality(
            0.4, 0.7, 0.3, 0.8, 0.6, 0.9
    );

    @Test
    void createsBaselinePersonAtVersionZero() {
        InMemoryRepository repository = new InMemoryRepository();
        PersonDirectoryService service = new PersonDirectoryService(repository, repository);

        PersonIdentity identity = new PersonIdentity(
                "沈知夏",
                LocalDate.parse("2006-04-18"),
                "女性",
                "上海",
                ZoneId.of("Asia/Shanghai"),
                Locale.SIMPLIFIED_CHINESE,
                List.of("大学生"),
                "视觉传达专业大三学生"
        );
        PersonDetails result = service.create(identity, PERSONALITY);

        assertEquals(0L, result.version());
        assertEquals(0, result.personEventCount());
        assertEquals(0, result.userEventCount());
        assertEquals("沈知夏", result.identity().displayName());
        assertEquals("Asia/Shanghai", result.identity().timeZone());
        assertEquals(0.4, result.personality().honestyHumility());
        assertEquals(0.5, result.state().energy());
        assertTrue(result.activeEffects().isEmpty());
        assertTrue(repository.findById(result.personId()).isPresent());
    }

    @Test
    void readsCompleteDetailsAndStateWithoutReturningAggregate() {
        InMemoryRepository repository = new InMemoryRepository();
        Person person = new Person(PERSONALITY);
        assertTrue(repository.insert(person));
        PersonDirectoryService service = new PersonDirectoryService(repository, repository);

        PersonDetails details = service.get(person.getId());
        PersonStateDetails state = service.getState(person.getId());

        assertEquals(person.getId(), details.personId());
        assertEquals(0L, details.version());
        assertEquals(person.getStateSnapshot(), details.state());
        assertEquals(person.getId(), state.personId());
        assertEquals(details.state(), state.state());
        assertEquals(details.activeEffects(), state.activeEffects());
    }

    @Test
    void missingPersonProducesDomainSpecificApplicationFailure() {
        InMemoryRepository repository = new InMemoryRepository();
        PersonDirectoryService service = new PersonDirectoryService(repository, repository);
        PersonId missingId = PersonId.random();

        assertThrows(PersonNotFoundException.class, () -> service.get(missingId));
        assertThrows(PersonNotFoundException.class, () -> service.getState(missingId));
    }

    @Test
    void rejectedInsertDoesNotPretendCreationSucceeded() {
        InMemoryRepository readRepository = new InMemoryRepository();
        PersonCreationRepository rejectedCreation = person -> false;
        PersonDirectoryService service = new PersonDirectoryService(
                readRepository,
                rejectedCreation
        );

        assertThrows(
                PersonCreationConflictException.class,
                () -> service.create(PERSONALITY)
        );
        assertTrue(readRepository.people.isEmpty());
    }

    private static final class InMemoryRepository
            implements PersonRepository, PersonCreationRepository {
        private final Map<PersonId, VersionedPerson> people = new HashMap<>();

        @Override
        public boolean insert(Person person) {
            if (people.containsKey(person.getId())) {
                return false;
            }
            people.put(person.getId(), new VersionedPerson(person.copy(), 0L));
            return true;
        }

        @Override
        public Optional<VersionedPerson> findById(PersonId personId) {
            VersionedPerson stored = people.get(personId);
            if (stored == null) {
                return Optional.empty();
            }
            return Optional.of(new VersionedPerson(stored.person().copy(), stored.version()));
        }

        @Override
        public boolean save(Person person, long expectedVersion) {
            VersionedPerson stored = people.get(person.getId());
            if (stored == null || stored.version() != expectedVersion) {
                return false;
            }
            people.put(
                    person.getId(),
                    new VersionedPerson(person.copy(), expectedVersion + 1)
            );
            return true;
        }
    }
}
