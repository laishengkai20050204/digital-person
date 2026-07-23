package com.laishengkai.digitalperson.web;

/** Raised when a protected internal HTTP boundary receives no valid token. */
public final class InvalidInternalTokenException extends RuntimeException {
    public InvalidInternalTokenException() {
        super("Invalid internal token");
    }
}
