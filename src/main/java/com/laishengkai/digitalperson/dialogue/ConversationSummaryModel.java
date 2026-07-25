package com.laishengkai.digitalperson.dialogue;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Generates a replacement rolling summary from the previous summary and one older turn batch. */
@FunctionalInterface
public interface ConversationSummaryModel {
    CompletionStage<String> summarize(
            Optional<String> existingSummary,
            List<ConversationTurnSnapshot> turns,
            ZoneId localTimeZone
    );
}
