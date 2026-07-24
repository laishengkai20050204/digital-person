package com.laishengkai.digitalperson.infrastructure.state;

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
import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectEndPolicy;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateTransition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageModelStateTransitionEvaluatorTest {

    @Test
    void shouldReadTypedEffectsWithCausesAndSerializeCompleteContext() {
        RecordingGateway gateway = new RecordingGateway(toolResponse("""
                {
                  "effects": [
                    {
                      "type": "EMOTIONAL",
                      "cause": "考试准备带来积极期待",
                      "transitions": [
                        {"dimension": "VALENCE", "direction": "INCREASE", "intensity": "HIGH"}
                      ],
                      "endPolicy": "FIXED_TIME",
                      "durationMinutes": 180
                    },
                    {
                      "type": "SOCIAL",
                      "cause": "当前交流暂时缓解孤独感",
                      "transitions": [
                        {"dimension": "LONELINESS", "direction": "DECREASE", "intensity": "MEDIUM"}
                      ],
                      "endPolicy": "EVENT_END",
                      "durationMinutes": 0
                    }
                  ]
                }
                """));
        LanguageModelStateTransitionEvaluator evaluator =
                new LanguageModelStateTransitionEvaluator(gateway);

        EventStateImpact impact = evaluator.evaluate(context())
                .toCompletableFuture()
                .join();

        assertEquals(2, impact.effects().size());
        assertEquals(StateEffectType.EMOTIONAL, impact.effects().getFirst().type());
        assertEquals("考试准备带来积极期待", impact.effects().getFirst().cause());
        StateTransition valence = impact.effects().getFirst().transitions().getFirst();
        assertEquals(StateDimension.VALENCE, valence.dimension());
        assertTrue(valence.shape() >= 0.40 && valence.shape() <= 0.60);
        assertEquals(
                StateEffectEndPolicy.FIXED_TIME,
                impact.effects().getFirst().endPolicy()
        );
        assertEquals(Duration.ofMinutes(180), impact.effects().getFirst().duration());
        assertEquals(StateEffectType.SOCIAL, impact.effects().get(1).type());
        assertEquals(
                StateEffectEndPolicy.EVENT_END,
                impact.effects().get(1).endPolicy()
        );
        assertEquals(Duration.ZERO, impact.effects().get(1).duration());

        LanguageModelRequest request = gateway.request();
        assertEquals(ModelToolChoice.REQUIRED, request.options().toolChoice());
        assertEquals(1, request.tools().size());
        assertEquals(
                LanguageModelStateTransitionEvaluator.TOOL_NAME,
                request.tools().getFirst().name()
        );
        String schema = request.tools().getFirst().parametersJsonSchema();
        assertTrue(schema.contains("cause"));
        assertTrue(schema.contains("endPolicy"));
        assertTrue(schema.contains("durationMinutes"));
        assertTrue(schema.contains("\"direction\""));
        assertTrue(schema.contains("\"intensity\""));
        assertTrue(schema.contains("\"INSTANT\""));
        UserModelMessage userMessage = assertInstanceOf(
                UserModelMessage.class,
                request.messages().get(1)
        );
        assertTrue(userMessage.text().contains("\"displayName\":\"沈知夏\""));
        assertTrue(userMessage.text().contains("\"activeEffects\""));
        assertTrue(userMessage.text().contains("既有考试压力"));
        assertTrue(userMessage.text().contains("\"activityType\":\"STUDY\""));
        assertTrue(userMessage.text().contains("\"valence\":0.2"));
        assertTrue(userMessage.text().contains("\"emotionality\":0.8"));
        assertTrue(userMessage.text().contains("\"section\":\"RELATIONSHIP\""));
        assertTrue(userMessage.text().contains("\"recentConversation\""));
        assertTrue(userMessage.text().contains("I miss you"));
    }

    @Test
    void shouldAcceptEmptyEffectSubmission() {
        LanguageModelStateTransitionEvaluator evaluator =
                new LanguageModelStateTransitionEvaluator(
                        ignored -> CompletableFuture.completedFuture(
                                toolResponse(emptyImpactJson())
                        )
                );

        assertEquals(
                EventStateImpact.none(),
                evaluator.evaluate(context()).toCompletableFuture().join()
        );
    }

    @Test
    void shouldRejectResponseWithoutSubmissionToolCall() {
        StateTransitionEvaluationException error = failureOf(evaluatorReturning(
                new LanguageModelResponse(
                        AssistantModelMessage.text("no change"),
                        ModelFinishReason.STOP,
                        ModelUsage.unknown()
                )
        ));
        assertTrue(error.getMessage().contains("exactly once"));
    }

    @Test
    void shouldRejectMultipleOrUnexpectedToolCalls() {
        StateTransitionEvaluationException multiple = failureOf(evaluatorReturning(
                new LanguageModelResponse(
                        AssistantModelMessage.toolCalls(List.of(
                                toolCall("call-1", emptyImpactJson()),
                                toolCall("call-2", emptyImpactJson())
                        )),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unknown()
                )
        ));
        assertTrue(multiple.getMessage().contains("received 2"));

        StateTransitionEvaluationException unexpected = failureOf(evaluatorReturning(
                new LanguageModelResponse(
                        AssistantModelMessage.toolCalls(List.of(
                                new ModelToolCall(
                                        "call-1",
                                        "another_tool",
                                        emptyImpactJson()
                                )
                        )),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unknown()
                )
        ));
        assertTrue(unexpected.getMessage().contains("unexpected"));
    }

    @Test
    void shouldRejectMalformedDuplicateInvalidOrInconsistentEffects() {
        assertTrue(failureOf(evaluatorReturning(
                toolResponse("not-json")
        )).getMessage().contains("invalid"));

        assertTrue(failureOf(evaluatorReturning(toolResponse("""
                {"effects":[{
                  "type":"EMOTIONAL",
                  "cause":"重复维度",
                  "transitions":[
                    {"dimension":"ENERGY","direction":"INCREASE","intensity":"LOW"},
                    {"dimension":"ENERGY","direction":"DECREASE","intensity":"LOW"}
                  ],
                  "endPolicy":"EVENT_END",
                  "durationMinutes":0
                }]}
                """))).getMessage().contains("duplicate"));

        assertTrue(failureOf(evaluatorReturning(toolResponse("""
                {"effects":[{
                  "type":"EMOTIONAL",
                  "cause":"未知方向",
                  "transitions":[{"dimension":"ENERGY","direction":"SIDEWAYS","intensity":"LOW"}],
                  "endPolicy":"EVENT_END",
                  "durationMinutes":0
                }]}
                """))).getMessage().contains("unknown effect direction"));

        assertTrue(failureOf(evaluatorReturning(toolResponse("""
                {"effects":[{
                  "type":"EMOTIONAL",
                  "cause":"瞬时效果持续过久",
                  "transitions":[{"dimension":"VALENCE","direction":"INCREASE","intensity":"INSTANT"}],
                  "endPolicy":"FIXED_TIME",
                  "durationMinutes":60
                }]}
                """))).getMessage().contains("INSTANT"));

        assertTrue(failureOf(evaluatorReturning(toolResponse("""
                {"effects":[{
                  "type":"EMOTIONAL",
                  "cause":"未知维度",
                  "transitions":[{"dimension":"MOOD","direction":"INCREASE","intensity":"LOW"}],
                  "endPolicy":"EVENT_END",
                  "durationMinutes":0
                }]}
                """))).getMessage().contains("unknown"));

        assertTrue(failureOf(evaluatorReturning(toolResponse("""
                {"effects":[{
                  "type":"EMOTIONAL",
                  "cause":"类型与维度不匹配",
                  "transitions":[{"dimension":"LONELINESS","direction":"INCREASE","intensity":"EXTREME"}],
                  "endPolicy":"FIXED_TIME",
                  "durationMinutes":60
                }]}
                """))).getMessage().contains("does not support"));

        assertTrue(failureOf(evaluatorReturning(toolResponse("""
                {"effects":[{
                  "type":"EMOTIONAL",
                  "cause":"固定时间缺少持续时间",
                  "transitions":[{"dimension":"VALENCE","direction":"DECREASE","intensity":"EXTREME"}],
                  "endPolicy":"FIXED_TIME",
                  "durationMinutes":0
                }]}
                """))).getMessage().contains("positive duration"));

        assertTrue(failureOf(evaluatorReturning(toolResponse("""
                {"effects":[{
                  "type":"EMOTIONAL",
                  "cause":"事件绑定却设置持续时间",
                  "transitions":[{"dimension":"VALENCE","direction":"DECREASE","intensity":"EXTREME"}],
                  "endPolicy":"EVENT_END",
                  "durationMinutes":60
                }]}
                """))).getMessage().contains("durationMinutes=0"));
    }

    private static StateTransitionEvaluationException failureOf(
            LanguageModelStateTransitionEvaluator evaluator
    ) {
        CompletionException error = assertThrows(
                CompletionException.class,
                () -> evaluator.evaluate(context()).toCompletableFuture().join()
        );
        return assertInstanceOf(
                StateTransitionEvaluationException.class,
                error.getCause()
        );
    }

    private static LanguageModelStateTransitionEvaluator evaluatorReturning(
            LanguageModelResponse response
    ) {
        return new LanguageModelStateTransitionEvaluator(
                ignored -> CompletableFuture.completedFuture(response)
        );
    }

    private static LanguageModelResponse toolResponse(String argumentsJson) {
        return new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(
                        toolCall("call-1", argumentsJson)
                )),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unknown()
        );
    }

    private static ModelToolCall toolCall(String id, String argumentsJson) {
        return new ModelToolCall(
                id,
                LanguageModelStateTransitionEvaluator.TOOL_NAME,
                argumentsJson
        );
    }

    private static String emptyImpactJson() {
        return "{\"effects\":[]}";
    }

    private static StateEvaluationContext context() {
        Instant now = Instant.parse("2026-07-22T03:00:00Z");
        PersonEvent event = new PersonEvent(
                ActivityType.STUDY,
                "Prepare for the exam",
                "library",
                TimeRange.openEnded(Instant.parse("2026-07-22T02:00:00Z"))
        );
        return new StateEvaluationContext(
                PersonId.random(),
                new PersonIdentitySnapshot(
                        "沈知夏",
                        java.time.LocalDate.parse("2006-04-18"),
                        20,
                        "女性",
                        "上海",
                        "Asia/Shanghai",
                        "zh-CN",
                        List.of("大学生"),
                        "视觉传达专业大三学生"
                ),
                new PersonalitySnapshot(0.6, 0.8, 0.4, 0.7, 0.9, 0.6),
                state(),
                List.of(new ActiveStateEffectSnapshot(
                        StateEffectType.COGNITIVE,
                        "既有考试压力",
                        event.getId().toString(),
                        now.minusSeconds(1800),
                        StateEffectEndPolicy.FIXED_TIME,
                        now.plusSeconds(1800),
                        List.of(new StateTransition(StateDimension.MENTAL_LOAD, 0.2))
                )),
                PersonEventSnapshot.from(PersonEventSnapshot.Owner.PERSON, event),
                List.of(PersonEventSnapshot.from(
                        PersonEventSnapshot.Owner.PERSON,
                        event
                )),
                List.of(),
                PersonMemoryContext.available(List.of(new MemoryItem(
                        "memory-1",
                        MemorySection.RELATIONSHIP,
                        "The relationship is stable and trusting.",
                        0.95,
                        now.minusSeconds(3600),
                        now
                ))),
                List.of(new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "I miss you",
                        now.minusSeconds(60)
                )),
                now
        );
    }

    private static PersonStateSnapshot state() {
        return new PersonStateSnapshot(
                0.2, 0.7, 0.3, 0.6, 0.4, 0.8,
                0.2, 0.1, 0.3, 0.4, 0.5
        );
    }

    private static final class RecordingGateway implements LanguageModelGateway {
        private final LanguageModelResponse response;
        private final AtomicReference<LanguageModelRequest> request =
                new AtomicReference<>();

        private RecordingGateway(LanguageModelResponse response) {
            this.response = response;
        }

        @Override
        public CompletionStage<LanguageModelResponse> invoke(
                LanguageModelRequest modelRequest
        ) {
            request.set(modelRequest);
            return CompletableFuture.completedFuture(response);
        }

        private LanguageModelRequest request() {
            return request.get();
        }
    }
}
