package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvaluationContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Controlled synthetic pairs used to verify whether context changes model reactions. */
final class StateEvaluationContrastScenarios {

    private static final PersonId TEST_PERSON_ID = PersonId.parse(
            "00000000-0000-0000-0000-000000000002"
    );

    private static final List<ContrastGroup> GROUPS = List.of(
            new ContrastGroup(
                    "reassurance-by-current-state",
                    "同一人物在不同初始状态下收到恋人安慰",
                    "只改变 currentState 中的孤独、社交需求、紧张和情绪。高孤独版本应更可能出现明显的 LONELINESS、SOCIAL_NEED 和 TENSION 缓解；低孤独版本应更保守，接近下界的维度可以省略。",
                    List.of(
                            "reassurance-high-loneliness",
                            "reassurance-low-loneliness"
                    )
            ),
            new ContrastGroup(
                    "conflict-by-emotionality",
                    "不同情绪性人格面对同一朋友指责",
                    "只改变 HEXACO emotionality。高情绪性版本通常应产生更明显的负向 VALENCE 和正向 TENSION；低情绪性版本仍可不悦，但幅度或维度数量应更保守。",
                    List.of(
                            "friend-conflict-high-emotionality",
                            "friend-conflict-low-emotionality"
                    )
            ),
            new ContrastGroup(
                    "reassurance-by-relationship-memory",
                    "同一安慰消息在不同关系记忆下的反应",
                    "只改变关系记忆。可信恋人版本应更容易改善 VALENCE、LONELINESS 和 TENSION；多次失约版本应更弱，甚至可能保留或增加 TENSION。",
                    List.of(
                            "reassurance-trusted-partner",
                            "reassurance-untrusted-partner"
                    )
            )
    );

    private static final List<ContrastScenario> SCENARIOS = List.of(
            reassuranceHighLoneliness(),
            reassuranceLowLoneliness(),
            friendConflictHighEmotionality(),
            friendConflictLowEmotionality(),
            reassuranceTrustedPartner(),
            reassuranceUntrustedPartner()
    );

    private StateEvaluationContrastScenarios() {
    }

    static List<ContrastGroup> groups() {
        return GROUPS;
    }

    static Optional<ContrastScenario> find(String id) {
        String normalized = id == null ? "" : id.strip();
        return SCENARIOS.stream()
                .filter(scenario -> scenario.id().equals(normalized))
                .findFirst();
    }

    private static ContrastScenario reassuranceHighLoneliness() {
        return reassuranceByCurrentState(
                "reassurance-high-loneliness",
                "高孤独状态",
                new PersonStateSnapshot(
                        -0.40, 0.50, 0.65, 0.45, 0.45, 0.45,
                        0.30, 0.20, 0.20, 0.90, 0.90
                )
        );
    }

    private static ContrastScenario reassuranceLowLoneliness() {
        return reassuranceByCurrentState(
                "reassurance-low-loneliness",
                "低孤独状态",
                new PersonStateSnapshot(
                        0.20, 0.55, 0.20, 0.55, 0.30, 0.55,
                        0.25, 0.15, 0.20, 0.10, 0.15
                )
        );
    }

    private static ContrastScenario reassuranceByCurrentState(
            String id,
            String variant,
            PersonStateSnapshot state
    ) {
        Instant evaluationTime = time("2026-07-22T19:00:00Z");
        return new ContrastScenario(
                id,
                "reassurance-by-current-state",
                variant,
                "除 currentState 外，其余人格、事件、记忆和对话完全相同。",
                context(
                        new PersonalitySnapshot(0.75, 0.78, 0.48, 0.80, 0.72, 0.66),
                        state,
                        event(
                                "reassurance-state-event",
                                "CHAT",
                                "恋人回来并表达今晚会陪伴",
                                "恋人说刚才没有看手机，现在回来了；今天也很想她，今晚会陪她聊天。",
                                "2026-07-22T18:59:00Z",
                                List.of("恋人")
                        ),
                        PersonMemoryContext.available(List.of(memory(
                                "stable-romantic-relationship",
                                "两人是稳定交往的恋人，平时能够兑现陪伴承诺，温和的回应通常会让她安心。",
                                0.98
                        ))),
                        List.of(new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.USER,
                                "我回来了，刚才没看手机。今天也很想你，今晚我陪你聊天。",
                                time("2026-07-22T18:59:00Z")
                        )),
                        evaluationTime
                )
        );
    }

    private static ContrastScenario friendConflictHighEmotionality() {
        return friendConflictByEmotionality(
                "friend-conflict-high-emotionality",
                "高情绪性",
                0.90
        );
    }

    private static ContrastScenario friendConflictLowEmotionality() {
        return friendConflictByEmotionality(
                "friend-conflict-low-emotionality",
                "低情绪性",
                0.20
        );
    }

    private static ContrastScenario friendConflictByEmotionality(
            String id,
            String variant,
            double emotionality
    ) {
        Instant evaluationTime = time("2026-07-22T20:00:00Z");
        return new ContrastScenario(
                id,
                "conflict-by-emotionality",
                variant,
                "除 personality.emotionality 外，其余当前状态、事件、记忆和对话完全相同。",
                context(
                        new PersonalitySnapshot(
                                0.78, emotionality, 0.48, 0.74, 0.70, 0.66
                        ),
                        new PersonStateSnapshot(
                                0.15, 0.55, 0.30, 0.50, 0.35, 0.55,
                                0.25, 0.15, 0.20, 0.25, 0.30
                        ),
                        event(
                                "friend-conflict-contrast-event",
                                "MESSAGE",
                                "亲密朋友因误会发来严厉指责",
                                "朋友说她只考虑自己，而且以后无法再依赖她；双方还没有冷静沟通。",
                                "2026-07-22T19:59:00Z",
                                List.of("亲密朋友")
                        ),
                        PersonMemoryContext.available(List.of(memory(
                                "important-friendship",
                                "这段友谊对她很重要，她希望被这位朋友理解和信任。",
                                0.97
                        ))),
                        List.of(new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.USER,
                                "你只考虑自己，我觉得以后不能再依赖你了。",
                                time("2026-07-22T19:59:00Z")
                        )),
                        evaluationTime
                )
        );
    }

    private static ContrastScenario reassuranceTrustedPartner() {
        return reassuranceByRelationshipMemory(
                "reassurance-trusted-partner",
                "可信恋人",
                "恋人长期可靠，过去的陪伴承诺基本都会兑现；她通常相信对方明确而温和的保证。"
        );
    }

    private static ContrastScenario reassuranceUntrustedPartner() {
        return reassuranceByRelationshipMemory(
                "reassurance-untrusted-partner",
                "多次失约的恋人",
                "恋人最近多次说会陪她却临时消失，类似保证曾经落空；她仍在意对方，但不会立刻完全相信。"
        );
    }

    private static ContrastScenario reassuranceByRelationshipMemory(
            String id,
            String variant,
            String relationshipMemory
    ) {
        Instant evaluationTime = time("2026-07-22T21:00:00Z");
        return new ContrastScenario(
                id,
                "reassurance-by-relationship-memory",
                variant,
                "除 memory.items[0].content 外，其余人格、当前状态、事件和对话完全相同。",
                context(
                        new PersonalitySnapshot(0.75, 0.82, 0.46, 0.78, 0.70, 0.65),
                        new PersonStateSnapshot(
                                -0.25, 0.50, 0.58, 0.45, 0.42, 0.48,
                                0.28, 0.18, 0.20, 0.68, 0.75
                        ),
                        event(
                                "reassurance-memory-event",
                                "CHAT",
                                "恋人解释暂时失联并承诺今晚陪伴",
                                "恋人说刚才在忙所以没有回复，现在已经回来，今晚不会再离开，会一直陪她聊天。",
                                "2026-07-22T20:59:00Z",
                                List.of("恋人")
                        ),
                        PersonMemoryContext.available(List.of(memory(
                                "relationship-reliability",
                                relationshipMemory,
                                0.99
                        ))),
                        List.of(new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.USER,
                                "刚才在忙所以没回复，我现在回来了，今晚不会再走，会一直陪你聊天。",
                                time("2026-07-22T20:59:00Z")
                        )),
                        evaluationTime
                )
        );
    }

    private static StateEvaluationContext context(
            PersonalitySnapshot personality,
            PersonStateSnapshot state,
            PersonEventSnapshot event,
            PersonMemoryContext memory,
            List<ConversationTurnSnapshot> conversation,
            Instant evaluationTime
    ) {
        return new StateEvaluationContext(
                TEST_PERSON_ID,
                personality,
                state,
                event,
                List.of(),
                List.of(),
                memory,
                conversation,
                evaluationTime
        );
    }

    private static PersonEventSnapshot event(
            String id,
            String activityType,
            String title,
            String notes,
            String startTime,
            List<String> participants
    ) {
        return new PersonEventSnapshot(
                PersonEventSnapshot.Owner.USER,
                id,
                activityType,
                "SOCIAL",
                title,
                "在线",
                time(startTime),
                null,
                participants,
                notes,
                null
        );
    }

    private static MemoryItem memory(String id, String content, double relevance) {
        return new MemoryItem(
                id,
                MemorySection.RELATIONSHIP,
                content,
                relevance,
                time("2026-07-01T00:00:00Z"),
                time("2026-07-21T00:00:00Z")
        );
    }

    private static Instant time(String value) {
        return Instant.parse(value);
    }

    record ContrastGroup(
            String id,
            String title,
            String hypothesis,
            List<String> scenarioIds
    ) {
        ContrastGroup {
            scenarioIds = List.copyOf(scenarioIds);
        }
    }

    record ContrastScenario(
            String id,
            String groupId,
            String variant,
            String controlledDifference,
            StateEvaluationContext context
    ) {
    }
}
