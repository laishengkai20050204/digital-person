package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.person.PersonId;

import java.util.Objects;
import java.util.Set;

/** Raised when a realtime command encounters active events that were never evaluated. */
public final class UnsettledPersonEventException extends RuntimeException {
    public UnsettledPersonEventException(
            PersonId personId,
            Set<ActivityChannel> pendingChannels
    ) {
        this(personId, pendingChannels, null);
    }

    public UnsettledPersonEventException(
            PersonId personId,
            Set<ActivityChannel> pendingChannels,
            Throwable cause
    ) {
        super(message(personId, pendingChannels), cause);
    }

    private static String message(
            PersonId personId,
            Set<ActivityChannel> pendingChannels
    ) {
        return "person has unevaluated active events before realtime command: personId="
                + Objects.requireNonNull(personId, "personId cannot be null")
                + ", pendingChannels="
                + Set.copyOf(Objects.requireNonNull(
                        pendingChannels,
                        "pendingChannels cannot be null"
                ));
    }
}
