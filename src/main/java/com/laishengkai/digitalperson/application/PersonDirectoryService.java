package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonCreationRepository;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentity;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;

import java.util.Objects;

/** Creates and reads persisted digital-person aggregates. */
public final class PersonDirectoryService {
    private final PersonRepository personRepository;
    private final PersonCreationRepository creationRepository;

    public PersonDirectoryService(
            PersonRepository personRepository,
            PersonCreationRepository creationRepository
    ) {
        this.personRepository = Objects.requireNonNull(
                personRepository,
                "personRepository cannot be null"
        );
        this.creationRepository = Objects.requireNonNull(
                creationRepository,
                "creationRepository cannot be null"
        );
    }

    /** Creates a baseline person and persists it at version zero. */
    public PersonDetails create(Personality personality) {
        return create(PersonIdentity.unspecified(), personality);
    }

    /** Creates a person with explicit stable identity and persists it at version zero. */
    public PersonDetails create(
            PersonIdentity identity,
            Personality personality
    ) {
        Person person = new Person(
                Objects.requireNonNull(identity, "identity cannot be null"),
                Objects.requireNonNull(personality, "personality cannot be null")
        );
        if (!creationRepository.insert(person)) {
            throw new PersonCreationConflictException(person.getId());
        }
        return PersonDetails.from(new VersionedPerson(person.copy(), 0L));
    }

    /** Returns one complete read model without exposing a mutable aggregate. */
    public PersonDetails get(PersonId personId) {
        return PersonDetails.from(load(personId));
    }

    /** Returns only the current short-term state and its persisted version. */
    public PersonStateDetails getState(PersonId personId) {
        return PersonStateDetails.from(load(personId));
    }

    private VersionedPerson load(PersonId personId) {
        PersonId requestedId = Objects.requireNonNull(personId, "personId cannot be null");
        return personRepository.findById(requestedId)
                .orElseThrow(() -> new PersonNotFoundException(requestedId));
    }
}
