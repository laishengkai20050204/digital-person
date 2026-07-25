package com.laishengkai.digitalperson.conversation;

import com.laishengkai.digitalperson.person.PersonId;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Application-owned port for atomically appending one completed dialogue exchange. */
@FunctionalInterface
public interface RecentConversationStore {
    CompletionStage<Integer> append(
            PersonId personId,
            List<ConversationTurnSnapshot> turns
    );
}
