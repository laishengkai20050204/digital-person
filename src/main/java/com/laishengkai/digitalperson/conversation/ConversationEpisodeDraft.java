package com.laishengkai.digitalperson.conversation;

import java.util.List;
import java.util.Objects;

/** One complete event extracted from an older, stable conversation batch. */
public record ConversationEpisodeDraft(
        String title,
        String summary,
        String eventType,
        List<String> participants,
        List<String> emotions,
        String outcome,
        double importance
) {
    public static final int MAX_TITLE_CHARACTERS = 200;
    public static final int MAX_SUMMARY_CHARACTERS = 4_000;
    public static final int MAX_OUTCOME_CHARACTERS = 2_000;
    public static final int MAX_LABEL_CHARACTERS = 80;
    public static final int MAX_LABELS = 12;

    public ConversationEpisodeDraft {
        title = requireBoundedText(title, "title", MAX_TITLE_CHARACTERS);
        summary = requireBoundedText(summary, "summary", MAX_SUMMARY_CHARACTERS);
        eventType = requireBoundedText(eventType, "eventType", MAX_LABEL_CHARACTERS)
                .toUpperCase(java.util.Locale.ROOT);
        participants = boundedLabels(participants, "participants");
        emotions = boundedLabels(emotions, "emotions");
        outcome = normalizeBounded(outcome, "outcome", MAX_OUTCOME_CHARACTERS);
        if (!Double.isFinite(importance) || importance < 0.0 || importance > 1.0) {
            throw new IllegalArgumentException("importance must be between 0.0 and 1.0");
        }
    }

    public String contextText() {
        StringBuilder text = new StringBuilder();
        text.append("事件：").append(title)
                .append("\n类型：").append(eventType)
                .append("\n经过：").append(summary);
        if (!participants.isEmpty()) {
            text.append("\n参与者：").append(String.join("、", participants));
        }
        if (!emotions.isEmpty()) {
            text.append("\n情绪：").append(String.join("、", emotions));
        }
        if (!outcome.isEmpty()) {
            text.append("\n结果：").append(outcome);
        }
        return text.toString();
    }

    private static List<String> boundedLabels(List<String> values, String fieldName) {
        List<String> safe = List.copyOf(Objects.requireNonNull(values, fieldName + " cannot be null"));
        if (safe.size() > MAX_LABELS) {
            throw new IllegalArgumentException(fieldName + " cannot contain more than " + MAX_LABELS + " values");
        }
        return safe.stream()
                .map(value -> requireBoundedText(value, fieldName, MAX_LABEL_CHARACTERS))
                .distinct()
                .toList();
    }

    private static String requireBoundedText(String value, String fieldName, int maxCharacters) {
        String normalized = normalizeBounded(value, fieldName, maxCharacters);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }

    private static String normalizeBounded(String value, String fieldName, int maxCharacters) {
        String normalized = Objects.requireNonNull(value, fieldName + " cannot be null").strip();
        if (normalized.length() > maxCharacters) {
            throw new IllegalArgumentException(fieldName + " cannot exceed " + maxCharacters + " characters");
        }
        return normalized;
    }
}
