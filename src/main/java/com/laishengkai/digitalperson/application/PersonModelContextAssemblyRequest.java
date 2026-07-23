package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.EventId;

import java.util.Objects;
import java.util.Set;

/** Query-specific options for assembling common person model background. */
public record PersonModelContextAssemblyRequest(
        Set<EventId> excludedEventIds,
        String relevanceSeed,
        boolean includeEventContextInRelevanceQuery,
        int maxMemoryItems,
        int maxConversationTurns
) {
    public PersonModelContextAssemblyRequest {
        excludedEventIds = Set.copyOf(Objects.requireNonNullElse(
                excludedEventIds,
                Set.of()
        ));
        if (excludedEventIds.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("excludedEventIds cannot contain null");
        }
        relevanceSeed = Objects.requireNonNullElse(relevanceSeed, "").strip();
        if (maxMemoryItems <= 0 || maxConversationTurns <= 0) {
            throw new IllegalArgumentException("retrieval limits must be positive");
        }
    }
}
