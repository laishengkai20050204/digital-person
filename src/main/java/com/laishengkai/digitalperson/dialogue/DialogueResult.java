package com.laishengkai.digitalperson.dialogue;

import com.laishengkai.digitalperson.person.Person;

import java.util.List;
import java.util.Objects;

/**
 * Final user-facing result returned by {@link Person#chatAsync(String)}.
 */
public record DialogueResult(
        String decisionSummary,
        List<String> replies
) {

    public DialogueResult {
        decisionSummary = decisionSummary == null ? "" : decisionSummary.strip();
        replies = List.copyOf(Objects.requireNonNull(replies, "replies cannot be null"));
    }

    public boolean isNoReply() {
        return replies.isEmpty();
    }
}
