package com.laishengkai.digitalperson.infrastructure.activity;

import com.laishengkai.digitalperson.activity.FinishActivityCommand;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LanguageModelPersonActivityDecisionFinishCompatibilityTest {

    @Test
    void ignoresKnownStartOnlyFieldsWhenProviderIncludesThemOnFinish() {
        EventId eventId = EventId.random();

        PersonActivityDecisionPlan plan = LanguageModelPersonActivityDecisionModel.parseResponse(
                toolResponse("""
                        {
                          "commands":[{
                            "action":"FINISH",
                            "eventId":"%s",
                            "reason":"COMPLETED",
                            "activityType":"STUDY",
                            "title":"模型重复的旧活动标题",
                            "location":"宿舍",
                            "participants":[],
                            "notes":"模型重复的旧活动备注"
                          }],
                          "nextReviewMinutes":15
                        }
                        """.formatted(eventId))
        );

        FinishActivityCommand command = assertInstanceOf(
                FinishActivityCommand.class,
                plan.commands().getFirst()
        );
        assertEquals(eventId, command.eventId());
        assertEquals(EventEndReason.COMPLETED, command.reason());
        assertEquals(15, plan.nextReviewMinutes());
    }

    @Test
    void stillRejectsPropertiesOutsideTheDeclaredToolContract() {
        EventId eventId = EventId.random();

        assertThrows(
                PersonActivityDecisionException.class,
                () -> LanguageModelPersonActivityDecisionModel.parseResponse(toolResponse("""
                        {
                          "commands":[{
                            "action":"FINISH",
                            "eventId":"%s",
                            "reason":"COMPLETED",
                            "inventedField":"must not be accepted"
                          }],
                          "nextReviewMinutes":15
                        }
                        """.formatted(eventId)))
        );
    }

    private static LanguageModelResponse toolResponse(String argumentsJson) {
        return new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(new ModelToolCall(
                        "call-1",
                        LanguageModelPersonActivityDecisionModel.TOOL_NAME,
                        argumentsJson
                ))),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unknown()
        );
    }
}
