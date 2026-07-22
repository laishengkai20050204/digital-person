package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

/** Raised when a stored aggregate document cannot be encoded or reconstituted safely. */
public final class PersonPersistenceException extends RuntimeException {
    public PersonPersistenceException(String message) {
        super(message);
    }

    public PersonPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
