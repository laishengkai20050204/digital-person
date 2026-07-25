package com.laishengkai.digitalperson.dialogue;

import com.laishengkai.digitalperson.conversation.ConversationEpisodeDraft;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Extracts zero or more complete event memories from one stable older-turn batch. */
@FunctionalInterface
public interface ConversationEpisodeModel {
    CompletionStage<List<ConversationEpisodeDraft>> extract(
            List<ConversationTurnSnapshot> turns,
            ZoneId localTimeZone
    );
}
