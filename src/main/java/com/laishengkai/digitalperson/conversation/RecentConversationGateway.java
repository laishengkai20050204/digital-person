package com.laishengkai.digitalperson.conversation;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Application-owned port for retrieving recent raw conversation turns. */
@FunctionalInterface
public interface RecentConversationGateway {
    CompletionStage<List<ConversationTurnSnapshot>> retrieve(
            RecentConversationQuery query
    );
}
