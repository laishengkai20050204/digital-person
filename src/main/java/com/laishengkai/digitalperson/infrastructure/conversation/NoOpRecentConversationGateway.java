package com.laishengkai.digitalperson.infrastructure.conversation;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Empty recent-conversation implementation used until chat persistence is connected. */
public final class NoOpRecentConversationGateway implements RecentConversationGateway {
    @Override
    public CompletionStage<List<ConversationTurnSnapshot>> retrieve(
            RecentConversationQuery query
    ) {
        Objects.requireNonNull(query, "query cannot be null");
        return CompletableFuture.completedFuture(List.of());
    }
}
