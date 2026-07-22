package com.laishengkai.digitalperson.person;

/** Persistence capability for creating a new digital-person aggregate. */
public interface PersonCreationRepository {

    /**
     * Inserts a new person at persistence version zero.
     *
     * @return {@code false} when the identifier already exists
     */
    boolean insert(Person person);
}
