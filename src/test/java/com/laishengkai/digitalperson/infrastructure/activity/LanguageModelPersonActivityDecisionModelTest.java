package com.laishengkai.digitalperson.infrastructure.activity;

import com.laishengkai.digitalperson.activity.FinishActivityCommand;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionContext;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.activity.StartActivityCommand;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentitySnapshot;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.ActiveStateEffectSnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectEndPolicy;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateTransition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageModelPersonActivityDecisionModelTest {

    @Test
    void parsesLifecyclePlanAndSendsStructuredContextThroughRequiredTool() {
        EventId activeEventId = EventId.random();
        RecordingGateway gateway = new RecordingGateway(toolResponse("""
                {
                  "commands": [
                    {
                      "action": "FINISH",
                      "eventId": "%s",
                      "reason": "COMPLETED"
                    },
                    {
                      "action": "START",
                      "activityType": "REST",
                      "title": "躺在床上休息",
                      "location": "宿舍",
                      "participants": [],
                      "notes": "完成学习后短暂休息"
                    }
                  ],
                  "nextReviewMinutes": 20
                }
                """.formatted(activeEventId)));
        LanguageModelPersonActivityDecisionModel model =
                new LanguageModelPersonActivityDecisionModel(gateway);

        PersonActivityDecisionPlan plan = model.decide(context(activeEventId))
                .toCompletableFuture()
                .join();

        FinishActivityCommand finish = assertInstanceOf(
                FinishActivityCommand.class,
                plan.commands().getFirst()
        );
        assertEquals(activeEventId, finish.eventId());
        assertEquals(EventEndReason.COMPLETED, finish.reason());
        StartActivityCommand start = assertInstanceOf(
                StartActivityCommand.class,
                plan.commands().get(1)
        );
        assertEquals(ActivityType.REST, start.activityType());
        assertEquals("躺在床上休息", start.title());
        assertEquals(20, plan.nextReviewMinutes());

        LanguageModelRequest request = gateway.request();
        assertEquals(ModelToolChoice.REQUIRED, request.options().toolChoice());
        assertEquals(1, request.tools().size());
        assertEquals(
                LanguageModelPersonActivityDecisionModel.TOOL_NAME,
                request.tools().getFirst().name()
        );
        String schema = request.tools().getFirst().parametersJsonSchema();
        assertTrue(schema.contains("START"));
        assertTrue(schema.contains("FINISH"));
        assertTrue(schema.contains("nextReviewMinutes"));
        assertFalse(schema.contains("REPLACED"));
        UserModelMessage userMessage = assertInstanceOf(
                UserModelMessage.class,
                request.messages().get(1)
        );
        assertTrue(userMessage.text().contains("\"displayName\":\"沈知夏\""));
        assertTrue(userMessage.text().contains("\"activeEffects\""));
        assertTrue(userMessage.text().contains(activeEventId.toString()));
        assertTrue(userMessage.text().contains("\"observation\":\"现在是晚上十一点"));
        assertTrue(userMessage.text().contains("\"section\":\"ROUTINE\""));
    }

    @Test
    void acceptsNoLifecycleChanges() {
        LanguageModelPersonActivityDecisionModel model =
                new LanguageModelPersonActivityDecisionModel(
                        ignored -> CompletableFuture.completedFuture(toolResponse("""
                                {"commands":[],"nextReviewMinutes":60}
                                """))
                );

        assertEquals(
                PersonActivityDecisionPlan.unchanged(60),
                model.decide(context(EventId.random())).toCompletableFuture().join()
        );
    }

    @Test
    void rejectsUnexpectedToolsAndSemanticallyInvalidCommands() {
        assertTrue(failureOf(new LanguageModelResponse(
                AssistantModelMessage.text("continue"),
                ModelFinishReason.STOP,
                ModelUsage.unknown()
        )).getMessage().contains("exactly once"));

        assertTrue(failureOf(toolResponse("""
                {
                  "commands":[{
                    "action":"FINISH",
                    "eventId":"%s",
                    "reason":"REPLACED"
                  }],
                  "nextReviewMinutes":15
                }
                """.formatted(EventId.random()))).getMessage().contains("unsupported"));

        assertTrue(failureOf(toolResponse("""
                {
                  "commands":[{
                    "action":"START",
                    "eventId":"%s",
                    "activityType":"REST",
                    "title":"休息",
                    "location":"",
                    "participants":[],
                    "notes":""
                  }],
                  "nextReviewMinutes":15
                }
                """.formatted(EventId.random()))).getMessage().contains("eventId"));

        assertTrue(failureOf(toolResponse("""
                {
                  "commands":[
                    {"action":"START","activityType":"STUDY","title":"学习","location":"","participants":[],"notes":""},
                    {"action":"START","activityType":"REST","title":"休息","location":"","participants":[],"notes":""}
                  ],
                  "nextReviewMinutes":15
                }
                """)).getMessage().contains("invalid"));
    }

    @Test
    void wrapsGatewayFailureAsActivityDecisionFailure() {
        LanguageModelPersonActivityDecisionModel model =
                new LanguageModelPersonActivityDecisionModel(
                        ignored -> CompletableFuture.failedFuture(
                                new IllegalStateException("provider unavailable")
                        )
                );

        PersonActivityDecisionException error = failureOf(model, context(EventId.random()));
        assertTrue(error.getMessage().contains("invocation failed"));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    private static PersonActivityDecisionException failureOf(
            LanguageModelResponse response
    ) {
        return failureOf(
                new LanguageModelPersonActivityDecisionModel(
                        ignored -> CompletableFuture.completedFuture(response)
                ),
                context(EventId.random())
        );
    }

    private static PersonActivityDecisionException failureOf(
            LanguageModelPersonActivityDecisionModel model,
            PersonActivityDecisionContext context
    ) {
        CompletionException error = assertThrows(
                CompletionException.class,
                () -> model.decide(context).toCompletableFuture().join()
        );
        return assertInstanceOf(
                PersonActivityDecisionException.class,
                error.getCause()
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

    private static PersonActivityDecisionContext context(EventId activeEventId) {
        Instant now = Instant.parse("2026-07-23T15:00:00Z");
        PersonEvent study = new PersonEvent(
                activeEventId,
                ActivityType.STUDY,
                "修改课程设计",
                "宿舍",
                TimeRange.openEnded(now.minusSeconds(7_200)),
                List.of(),
                "已经连续学习两小时"
        );
        return new PersonActivityDecisionContext(
                PersonId.random(),
                new PersonIdentitySnapshot(
                        "沈知夏",
                        LocalDate.parse("2006-04-18"),
                        20,
                        "女性",
                        "上海",
                        "Asia/Shanghai",
                        "zh-CN",
                        List.of("大学生"),
                        "视觉传达专业大三学生"
                ),
                new PersonalitySnapshot(0.7, 0.8, 0.4, 0.7, 0.6, 0.8),
                new PersonStateSnapshot(
                        0.1, 0.2, 0.3,
                        0.4, 0.6, 0.3,
                        0.8, 0.75, 0.4,
                        0.2, 0.3
                ),
                List.of(new ActiveStateEffectSnapshot(
                        StateEffectType.PHYSICAL,
                        "持续学习积累疲劳",
                        activeEventId.toString(),
                        now.minusSeconds(3_600),
                        StateEffectEndPolicy.EVENT_END,
                        null,
                        List.of(new StateTransition(StateDimension.FATIGUE, 0.3))
                )),
                List.of(PersonEventSnapshot.from(
                        PersonEventSnapshot.Owner.PERSON,
                        study
                )),
                List.of(),
                PersonMemoryContext.available(List.of(new MemoryItem(
                        "routine-1",
                        MemorySection.ROUTINE,
                        "通常晚上十一点半左右睡觉",
                        0.9,
                        now.minusSeconds(86_400),
                        now
                ))),
                List.of(new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "早点休息吧",
                        now.minusSeconds(60)
                )),
                "现在是晚上十一点，人物已经明显困倦",
                now
        );
    }

    private static final class RecordingGateway implements LanguageModelGateway {
        private final LanguageModelResponse response;
        private final AtomicReference<LanguageModelRequest> request = new AtomicReference<>();

        private RecordingGateway(LanguageModelResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<LanguageModelResponse> invoke(LanguageModelRequest value) {
            request.set(value);
            return CompletableFuture.completedFuture(response);
        }

        private LanguageModelRequest request() {
            return request.get();
        }
    }
}
