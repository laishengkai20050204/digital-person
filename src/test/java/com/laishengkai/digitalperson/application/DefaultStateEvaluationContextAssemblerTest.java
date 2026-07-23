package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonIdentity;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.EffectId;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectEndPolicy;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStateEvaluationContextAssemblerTest {
    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");

    @Test
    void assemblesPersonalityEventsMemoryConversationAndRuntimeTime() {
        Person person = new Person(
                new PersonIdentity(
                        "沈知夏",
                        LocalDate.parse("2006-04-18"),
                        "女性",
                        "上海",
                        ZoneId.of("Asia/Shanghai"),
                        Locale.SIMPLIFIED_CHINESE,
                        List.of("大学生", "视觉传达专业学生"),
                        "大三学生"
                ),
                new Personality(0.6, 0.8, 0.4, 0.7, 0.9, 0.5)
        );
        PersonEvent study = event(ActivityType.STUDY, "Prepare exam", 600);
        PersonEvent music = event(ActivityType.LISTEN_MUSIC, "Background music", 500);
        PersonEvent userChat = event(ActivityType.CHAT, "User sent a message", 60);
        PersonEvent earlierPersonEvent = completedEvent(
                ActivityType.EAT,
                "Breakfast",
                1_200,
                1_100
        );
        PersonEvent earlierUserEvent = completedEvent(
                ActivityType.CHAT,
                "Earlier message",
                900,
                800
        );
        person.recordPersonEvent(earlierPersonEvent, NOW);
        person.recordUserEvent(earlierUserEvent, NOW);
        person.startPersonEvent(study, NOW);
        person.startPersonEvent(music, NOW);
        person.startUserEvent(userChat, NOW);

        RegisteredStateEffect musicEffect = new RegisteredStateEffect(
                EffectId.random(),
                music.getId(),
                StateEffectType.EMOTIONAL,
                "轻音乐带来放松感",
                music.getStartTime(),
                StateEffectEndPolicy.EVENT_END,
                null,
                List.of(new StateTransition(StateDimension.TENSION, -0.4))
        );
        StateEvolutionContext evolution = new StateEvolutionContext(
                NOW.minusSeconds(300),
                Map.of(musicEffect.effectId(), musicEffect),
                Set.of(music.getId())
        );
        person.commitStateUpdate(person.getState(), evolution);

        AtomicReference<PersonMemoryQuery> receivedQuery = new AtomicReference<>();
        DefaultStateEvaluationContextAssembler assembler =
                new DefaultStateEvaluationContextAssembler(
                        query -> {
                            receivedQuery.set(query);
                            return CompletableFuture.completedFuture(
                                    PersonMemoryContext.available(List.of(new MemoryItem(
                                            "relationship-1",
                                            MemorySection.RELATIONSHIP,
                                            "Stable romantic relationship",
                                            0.9,
                                            NOW.minusSeconds(3600),
                                            NOW
                                    )))
                            );
                        },
                        query -> CompletableFuture.completedFuture(List.of(
                                new ConversationTurnSnapshot(
                                        ConversationTurnSnapshot.Role.USER,
                                        "Are you still studying?",
                                        NOW.minusSeconds(30)
                                )
                        ))
                );

        StateEvaluationContext context = assembler.assemble(
                person,
                person.getStateSnapshot(),
                evolution,
                study,
                NOW
        ).toCompletableFuture().join();

        assertEquals(person.getId(), context.personId());
        assertEquals("沈知夏", context.identity().displayName());
        assertEquals(20, context.identity().age());
        assertEquals("Asia/Shanghai", context.identity().timeZone());
        assertEquals(0.8, context.personality().emotionality());
        assertEquals(1, context.activeEffects().size());
        assertEquals("轻音乐带来放松感", context.activeEffects().getFirst().cause());
        assertEquals(music.getId().toString(), context.activeEffects().getFirst().sourceEventId());
        assertEquals(NOW, context.evaluationTime());
        assertEquals(1, context.memory().items().size());
        assertEquals(1, context.recentConversation().size());
        assertEquals(
                List.of(music.getId().toString(), userChat.getId().toString()),
                context.activeEvents().stream()
                        .map(PersonEventSnapshot::eventId)
                        .toList()
        );
        assertEquals(
                List.of(
                        earlierPersonEvent.getId().toString(),
                        earlierUserEvent.getId().toString()
                ),
                context.recentEvents().stream()
                        .map(PersonEventSnapshot::eventId)
                        .toList()
        );
        assertFalse(context.activeEvents().stream().anyMatch(
                active -> active.eventId().equals(study.getId().toString())
        ));
        assertFalse(context.recentEvents().stream().anyMatch(
                recent -> recent.eventId().equals(study.getId().toString())
                        || recent.eventId().equals(music.getId().toString())
                        || recent.eventId().equals(userChat.getId().toString())
        ));
        assertTrue(receivedQuery.get().sections().contains(MemorySection.COMMITMENT));
        assertTrue(receivedQuery.get().relevanceQuery().contains("Prepare exam"));
    }

    private static PersonEvent event(
            ActivityType type,
            String title,
            long secondsAgo
    ) {
        return new PersonEvent(
                type,
                title,
                "",
                TimeRange.openEnded(NOW.minusSeconds(secondsAgo))
        );
    }

    private static PersonEvent completedEvent(
            ActivityType type,
            String title,
            long startSecondsAgo,
            long endSecondsAgo
    ) {
        return new PersonEvent(
                type,
                title,
                "",
                TimeRange.closed(
                        NOW.minusSeconds(startSecondsAgo),
                        NOW.minusSeconds(endSecondsAgo)
                )
        );
    }
}
