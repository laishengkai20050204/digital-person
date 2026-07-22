package com.laishengkai.digitalperson.conversation;

import com.laishengkai.digitalperson.person.PersonId;

import java.util.Objects;

/** Request for the recent raw conversation relevant to one evaluation. */
public record RecentConversationQuery(
        PersonId personId,
        String relevanceQuery,
        int maxTurns
) {
    public RecentConversationQuery {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        relevanceQuery = Objects.requireNonNullElse(relevanceQuery, "").strip();
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("maxTurns must be positive");
        }
    }
}
