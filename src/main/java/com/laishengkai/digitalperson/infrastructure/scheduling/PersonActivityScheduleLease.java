package com.laishengkai.digitalperson.infrastructure.scheduling;

import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One database-backed exclusive claim for an autonomous activity-decision cycle. */
public record PersonActivityScheduleLease(
        PersonId personId,
        UUID leaseToken,
        int failureCount,
        Instant claimedAt,
        Instant leaseUntil
) {
    public PersonActivityScheduleLease {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        leaseToken = Objects.requireNonNull(leaseToken, "leaseToken cannot be null");
        if (failureCount < 0) {
            throw new IllegalArgumentException("failureCount cannot be negative");
        }
        claimedAt = Objects.requireNonNull(claimedAt, "claimedAt cannot be null");
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil cannot be null");
        if (!leaseUntil.isAfter(claimedAt)) {
            throw new IllegalArgumentException("leaseUntil must be after claimedAt");
        }
    }
}