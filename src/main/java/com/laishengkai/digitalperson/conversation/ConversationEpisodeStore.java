package com.laishengkai.digitalperson.conversation;

import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Persistence port for event memories extracted from committed rolling-summary batches. */
public interface ConversationEpisodeStore extends ConversationEpisodeGateway {
    CompletionStage<Integer> saveAll(
            PersonId personId,
            ConversationSummaryWorkItem workItem,
            List<ConversationEpisodeDraft> episodes,
            Instant extractedAt
    );
}
