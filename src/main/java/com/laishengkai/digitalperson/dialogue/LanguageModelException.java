package com.laishengkai.digitalperson.dialogue;

/** Raised when the configured model provider cannot complete a request. */
public final class LanguageModelException extends RuntimeException {

    public LanguageModelException(String message) {
        super(message);
    }

    public LanguageModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
