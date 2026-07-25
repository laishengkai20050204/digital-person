package com.laishengkai.digitalperson.conversation;

import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Persistence port for preparing and atomically committing rolling summary updates. */
public interface ConversationSummaryStore extends ConversationSummaryGateway {
    CompletionStage<Optional<ConversationSummaryWorkItem>> findWork(
            PersonId personId,
            int recentTurnsToKeep,
            int batchTurns
    );

    CompletionStage<Boolean> save(
            PersonId personId,
            ConversationSummaryWorkItem workItem,
            String summary,
            Instant summarizedAt
    );
}
