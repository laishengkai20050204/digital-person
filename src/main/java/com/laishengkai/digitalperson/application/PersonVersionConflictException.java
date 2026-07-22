package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.person.PersonId;

/** Raised when an asynchronous update tries to save an obsolete aggregate version. */
public final class PersonVersionConflictException extends RuntimeException {
    public PersonVersionConflictException(PersonId personId, long expectedVersion) {
        super(
                "person changed while the operation was in progress: personId="
                        + personId
                        + ", expectedVersion="
                        + expectedVersion
        );
    }
}
