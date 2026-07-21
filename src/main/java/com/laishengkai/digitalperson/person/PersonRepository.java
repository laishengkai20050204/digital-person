package com.laishengkai.digitalperson.person;

import java.util.Optional;

public interface PersonRepository {
    Optional<Person> findById(PersonId personId);

    void save(Person person);
}
