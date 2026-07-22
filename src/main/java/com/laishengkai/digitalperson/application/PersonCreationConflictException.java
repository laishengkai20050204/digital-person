package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.person.PersonId;

/** Raised when a newly generated person identifier already exists in persistence. */
public final class PersonCreationConflictException extends RuntimeException {
    public PersonCreationConflictException(PersonId personId) {
        super("person identifier already exists: " + personId);
    }
}
