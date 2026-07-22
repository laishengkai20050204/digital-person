package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.person.PersonId;

import java.util.Set;

/** Raised when a realtime command encounters active events that were never evaluated. */
public final class UnsettledPersonEventException extends RuntimeException {
    public UnsettledPersonEventException(
            PersonId personId,
            Set<ActivityChannel> pendingChannels
    ) {
        super(
                "person has unevaluated active events before realtime command: personId="
                        + personId
                        + ", pendingChannels="
                        + Set.copyOf(pendingChannels)
        );
    }
}
