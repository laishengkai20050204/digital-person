package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.person.PersonId;

public final class PersonNotFoundException extends RuntimeException {
    public PersonNotFoundException(PersonId personId) {
        super("person does not exist: " + personId);
    }
}
