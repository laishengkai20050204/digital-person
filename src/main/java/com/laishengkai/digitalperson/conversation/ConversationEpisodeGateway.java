package com.laishengkai.digitalperson.conversation;

import com.laishengkai.digitalperson.person.PersonId;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Application-owned port for retrieving relevant persisted conversation episodes. */
@FunctionalInterface
public interface ConversationEpisodeGateway {
    CompletionStage<List<ConversationEpisodeSnapshot>> retrieve(
            PersonId personId,
            String relevanceQuery,
            int maxItems
    );
}
