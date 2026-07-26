package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.ModelUsage;

import java.util.Objects;

/** Adds nullable provider token counters without inventing unknown values. */
final class AgentUsageAccumulator {
    private AgentUsageAccumulator() {
    }

    static ModelUsage add(ModelUsage left, ModelUsage right) {
        return new ModelUsage(
                addNullable(left.inputTokens(), right.inputTokens()),
                addNullable(left.outputTokens(), right.outputTokens()),
                addNullable(left.totalTokens(), right.totalTokens())
        );
    }

    private static Integer addNullable(Integer left, Integer right) {
        if (left == null && right == null) {
            return null;
        }
        return Objects.requireNonNullElse(left, 0)
                + Objects.requireNonNullElse(right, 0);
    }
}
