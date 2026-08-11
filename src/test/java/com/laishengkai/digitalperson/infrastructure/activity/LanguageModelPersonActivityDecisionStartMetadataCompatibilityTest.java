package com.laishengkai.digitalperson.infrastructure.activity;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.activity.StartActivityCommand;
import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.experience.ActivityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class LanguageModelPersonActivityDecisionStartMetadataCompatibilityTest {

    @Test
    void defaultsOptionalStartMetadataWhenProviderOmitsIt() {
        LanguageModelResponse response = new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(new ModelToolCall(
                        "call-1",
                        LanguageModelPersonActivityDecisionModel.TOOL_NAME,
                        """
                                {
                                  "commands":[{
                                    "action":"START",
                                    "activityType":"ENTERTAINMENT",
                                    "title":"打王者"
                                  }],
                                  "nextReviewMinutes":30
                                }
                                """
                ))),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unknown()
        );

        PersonActivityDecisionPlan plan =
                LanguageModelPersonActivityDecisionModel.parseResponse(response);

        StartActivityCommand start = assertInstanceOf(
                StartActivityCommand.class,
                plan.commands().getFirst()
        );
        assertEquals(ActivityType.ENTERTAINMENT, start.activityType());
        assertEquals("打王者", start.title());
        assertEquals("", start.location());
        assertEquals(List.of(), start.participants());
        assertEquals("", start.notes());
        assertEquals(30, plan.nextReviewMinutes());
    }
}
