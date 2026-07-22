package com.laishengkai.digitalperson.dialogue;

/** Token usage reported by one provider invocation; null fields mean unknown. */
public record ModelUsage(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {
    public ModelUsage {
        validate(inputTokens, "inputTokens");
        validate(outputTokens, "outputTokens");
        validate(totalTokens, "totalTokens");
    }

    public static ModelUsage unknown() {
        return new ModelUsage(null, null, null);
    }

    private static void validate(Integer value, String fieldName) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative");
        }
    }
}
