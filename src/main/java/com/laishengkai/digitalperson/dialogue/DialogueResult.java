package com.laishengkai.digitalperson.dialogue;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Final user-facing dialogue result produced by application orchestration. */
public record DialogueResult(
        String decisionSummary,
        List<String> replies
) {
    private static final Pattern LEADING_INTERNAL_TIMESTAMP = Pattern.compile(
            "^\\s*\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?: [^\\]\\r\\n]+)?\\]\\s*"
    );

    public DialogueResult {
        decisionSummary = decisionSummary == null ? "" : decisionSummary.strip();
        replies = List.copyOf(Objects.requireNonNull(replies, "replies cannot be null")
                .stream()
                .map(DialogueResult::sanitizeReply)
                .toList());
    }

    public boolean isNoReply() {
        return replies.isEmpty();
    }

    private static String sanitizeReply(String reply) {
        String sanitized = Objects.requireNonNull(reply, "replies cannot contain null").strip();
        while (true) {
            var matcher = LEADING_INTERNAL_TIMESTAMP.matcher(sanitized);
            if (!matcher.find()) {
                return sanitized;
            }
            sanitized = sanitized.substring(matcher.end()).stripLeading();
        }
    }
}
