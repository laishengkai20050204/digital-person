package com.laishengkai.digitalperson.infrastructure.conversation;

import com.laishengkai.digitalperson.conversation.ConversationSummaryGateway;
import com.laishengkai.digitalperson.conversation.ConversationSummarySnapshot;
import com.laishengkai.digitalperson.person.PersonId;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Empty rolling-summary provider used when MySQL conversation persistence is disabled. */
public final class NoOpConversationSummaryGateway implements ConversationSummaryGateway {
    @Override
    public CompletionStage<Optional<ConversationSummarySnapshot>> retrieve(PersonId personId) {
        Objects.requireNonNull(personId, "personId cannot be null");
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
