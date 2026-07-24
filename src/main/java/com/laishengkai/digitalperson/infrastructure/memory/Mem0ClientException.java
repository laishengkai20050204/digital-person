package com.laishengkai.digitalperson.infrastructure.memory;

/** Indicates a protocol, transport or non-success response from Mem0. */
public final class Mem0ClientException extends RuntimeException {
    public Mem0ClientException(String message) {
        super(message);
    }

    public Mem0ClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
