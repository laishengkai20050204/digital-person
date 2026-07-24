package com.laishengkai.digitalperson.dialogue;

/** Indicates that a direct person dialogue reply could not be generated safely. */
public final class PersonDialogueException extends RuntimeException {
    public PersonDialogueException(String message) {
        super(message);
    }

    public PersonDialogueException(String message, Throwable cause) {
        super(message, cause);
    }
}
