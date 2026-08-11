package com.laishengkai.digitalperson.infrastructure.activity;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageModelPersonActivityDecisionMissingIntervalCompatibilityTest {

    @Test
    void defaultsReviewIntervalWhenProviderOmitsSchedulingHint() {
        LanguageModelResponse response = new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(new ModelToolCall(
                        "call-1",
                        LanguageModelPersonActivityDecisionModel.TOOL_NAME,
                        "{\"commands\":[]}"
                ))),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unknown()
        );

        PersonActivityDecisionPlan plan =
                LanguageModelPersonActivityDecisionModel.parseResponse(response);

        assertEquals(
                PersonActivityDecisionPlan.unchanged(
                        LanguageModelPersonActivityDecisionModel.DEFAULT_NEXT_REVIEW_MINUTES
                ),
                plan
        );
    }
}
