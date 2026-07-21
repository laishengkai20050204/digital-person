package com.laishengkai.digitalperson.dialogue;

import java.util.Objects;

public record DialogueDecision(
        boolean shouldReply,
        ReplyIntent intent,
        String guidance
) {

    public DialogueDecision {
        intent = Objects.requireNonNull(intent, "intent cannot be null");
        guidance = guidance == null ? "" : guidance.strip();

        if (!shouldReply && intent != ReplyIntent.NO_REPLY) {
            throw new IllegalArgumentException(
                    "intent must be NO_REPLY when shouldReply is false"
            );
        }

        if (shouldReply && intent == ReplyIntent.NO_REPLY) {
            throw new IllegalArgumentException(
                    "intent cannot be NO_REPLY when shouldReply is true"
            );
        }
    }

    public static DialogueDecision noReply(String guidance) {
        return new DialogueDecision(false, ReplyIntent.NO_REPLY, guidance);
    }
}
