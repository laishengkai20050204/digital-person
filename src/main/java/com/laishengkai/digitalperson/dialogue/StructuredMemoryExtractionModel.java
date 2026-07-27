package com.laishengkai.digitalperson.dialogue;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.memory.StructuredMemoryExtraction;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Extracts conservative typed entities and facts from one committed dialogue batch. */
@FunctionalInterface
public interface StructuredMemoryExtractionModel {
    CompletionStage<StructuredMemoryExtraction> extract(
            List<ConversationTurnSnapshot> turns,
            ZoneId localTimeZone
    );
}
