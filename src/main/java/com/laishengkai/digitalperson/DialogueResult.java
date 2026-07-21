package com.laishengkai.digitalperson;

import java.util.List;
import java.util.Objects;

/**
 * Final user-facing result returned by {@link Person#chatAsync(String)}.
 */
public record DialogueResult(
        Outcome outcome,
        String decisionSummary,
        List<ReplySegment> replySegments,
        Long reconsiderAfterMillis
) {

    public DialogueResult {
        outcome = Objects.requireNonNull(outcome, "outcome cannot be null");
        decisionSummary = decisionSummary == null ? "" : decisionSummary.strip();
        replySegments = List.copyOf(Objects.requireNonNull(
                replySegments,
                "replySegments cannot be null"
        ));

        if (reconsiderAfterMillis != null && reconsiderAfterMillis < 0) {
            throw new IllegalArgumentException("reconsiderAfterMillis cannot be negative");
        }

        switch (outcome) {
            case NO_REPLY -> {
                if (!replySegments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "NO_REPLY cannot contain reply segments"
                    );
                }
            }
            case REPLY -> {
                if (replySegments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "REPLY requires at least one reply segment"
                    );
                }
                if (reconsiderAfterMillis != null) {
                    throw new IllegalArgumentException(
                            "REPLY cannot contain reconsiderAfterMillis"
                    );
                }
            }
        }
    }

    public enum Outcome {
        NO_REPLY,
        REPLY
    }

    public record ReplySegment(String content, long delayAfterPreviousMillis) {

        public ReplySegment {
            content = Objects.requireNonNull(content, "content cannot be null").strip();
            if (content.isEmpty()) {
                throw new IllegalArgumentException("content cannot be blank");
            }
            if (delayAfterPreviousMillis < 0) {
                throw new IllegalArgumentException(
                        "delayAfterPreviousMillis cannot be negative"
                );
            }
        }
    }
}
