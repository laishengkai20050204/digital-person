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
import com.laishengkai.digitalperson.modelcontext.PersonModelContextSnapshot;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonIdentity;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPersonModelContextAssemblerTest {
    private static final Instant NOW = Instant.parse("2026-07-25T15:00:00Z");

    @Test
    void assemblesSharedTemporalAndEventTiming() {
        Person person = new Person(
                new PersonIdentity(
                        "沈知夏",
                        LocalDate.parse("2006-04-18"),
                        "女性",
                        "上海",
                        ZoneId.of("Asia/Shanghai"),
                        Locale.SIMPLIFIED_CHINESE,
                        List.of("大学生"),
                        "视觉传达专业大三学生"
                ),
                new Personality(0.7, 0.8, 0.4, 0.7, 0.6, 0.8)
        );
        PersonEvent study = new PersonEvent(
                ActivityType.STUDY,
                "修改课程设计",
                "宿舍",
                TimeRange.openEnded(NOW.minusSeconds(9_000))
        );
        PersonEvent breakfast = new PersonEvent(
                ActivityType.EAT,
                "早餐",
                "食堂",
                TimeRange.closed(
                        NOW.minusSeconds(25_200),
                        NOW.minusSeconds(23_400)
                )
        );
        person.startPersonEvent(study, NOW);
        person.recordPersonEvent(breakfast, NOW);

        AtomicReference<PersonMemoryQuery> receivedQuery = new AtomicReference<>();
        DefaultPersonModelContextAssembler assembler =
                new DefaultPersonModelContextAssembler(
                        query -> {
                            receivedQuery.set(query);
                            return CompletableFuture.completedFuture(
                                    PersonMemoryContext.disabled()
                            );
                        },
                        query -> CompletableFuture.completedFuture(List.of())
                );

        PersonModelContextSnapshot context = assembler.assemble(
                person,
                person.getStateSnapshot(),
                StateEvolutionContext.initial(),
                new PersonModelContextAssemblyRequest(
                        Set.of(),
                        "已经晚上十一点",
                        true,
                        30,
                        20
                ),
                NOW
        ).toCompletableFuture().join();

        assertEquals(NOW, context.temporal().now());
        assertEquals("Asia/Shanghai", context.temporal().timeZone());
        assertEquals(23, context.temporal().hourOfDay());
        assertEquals(DayOfWeek.SATURDAY, context.temporal().dayOfWeek());
        assertTrue(context.temporal().weekend());

        PersonEventSnapshot active = context.activeEvents().getFirst();
        assertTrue(active.active());
        assertEquals(150L, active.elapsedMinutes());
        assertNull(active.minutesSinceEnd());

        PersonEventSnapshot recent = context.recentEvents().getFirst();
        assertFalse(recent.active());
        assertEquals(30L, recent.elapsedMinutes());
        assertEquals(390L, recent.minutesSinceEnd());

        assertTrue(receivedQuery.get().relevanceQuery().contains("已经晚上十一点"));
        assertTrue(receivedQuery.get().relevanceQuery().contains("修改课程设计"));
        assertEquals(30, receivedQuery.get().maxItems());
    }

    @Test
    void excludesNewEventAndKeepsStateQueryFocused() {
        Person person = new Person(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        PersonEvent study = new PersonEvent(
                ActivityType.STUDY,
                "准备考试",
                "图书馆",
                TimeRange.openEnded(NOW.minusSeconds(600))
        );
        PersonEvent music = new PersonEvent(
                ActivityType.LISTEN_MUSIC,
                "听音乐",
                "宿舍",
                TimeRange.openEnded(NOW.minusSeconds(300))
        );
        person.startPersonEvent(study, NOW);
        person.startPersonEvent(music, NOW);

        AtomicReference<PersonMemoryQuery> receivedQuery = new AtomicReference<>();
        DefaultPersonModelContextAssembler assembler =
                new DefaultPersonModelContextAssembler(
                        query -> {
                            receivedQuery.set(query);
                            return CompletableFuture.completedFuture(
                                    PersonMemoryContext.disabled()
                            );
                        },
                        query -> CompletableFuture.completedFuture(List.of())
                );

        PersonModelContextSnapshot context = assembler.assemble(
                person,
                person.getStateSnapshot(),
                StateEvolutionContext.initial(),
                new PersonModelContextAssemblyRequest(
                        Set.of(study.getId()),
                        "STUDY\n准备考试\n图书馆",
                        false,
                        20,
                        20
                ),
                NOW
        ).toCompletableFuture().join();

        assertEquals(1, context.activeEvents().size());
        assertEquals(music.getId().toString(), context.activeEvents().getFirst().eventId());
        assertFalse(context.recentEvents().stream().anyMatch(event ->
                event.eventId().equals(study.getId().toString())
        ));
        assertEquals(
                "STUDY\n准备考试\n图书馆",
                receivedQuery.get().relevanceQuery()
        );
    }
    @Test
    void boundsMemoryConversationAndRelevanceQueryByCharacters() {
        Person person = new Person(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        AtomicReference<PersonMemoryQuery> receivedQuery = new AtomicReference<>();
        DefaultPersonModelContextAssembler assembler =
                new DefaultPersonModelContextAssembler(
                        query -> {
                            receivedQuery.set(query);
                            return CompletableFuture.completedFuture(
                                    PersonMemoryContext.available(List.of(
                                            new MemoryItem(
                                                    "memory-1",
                                                    MemorySection.EPISODIC,
                                                    "abcdefghijklmnop",
                                                    0.9,
                                                    NOW.minusSeconds(60),
                                                    NOW
                                            ),
                                            new MemoryItem(
                                                    "memory-2",
                                                    MemorySection.PREFERENCE,
                                                    "qrstuvwxyz",
                                                    0.8,
                                                    NOW.minusSeconds(30),
                                                    NOW
                                            )
                                    ))
                            );
                        },
                        query -> CompletableFuture.completedFuture(List.of(
                                new ConversationTurnSnapshot(
                                        ConversationTurnSnapshot.Role.USER,
                                        "old-turn",
                                        NOW.minusSeconds(20)
                                ),
                                new ConversationTurnSnapshot(
                                        ConversationTurnSnapshot.Role.PERSON,
                                        "latest-turn-is-long",
                                        NOW.minusSeconds(10)
                                )
                        )),
                        Duration.ofHours(24),
                        12,
                        10,
                        15
                );

        PersonModelContextSnapshot context = assembler.assemble(
                person,
                person.getStateSnapshot(),
                StateEvolutionContext.initial(),
                new PersonModelContextAssemblyRequest(
                        Set.of(),
                        "012345678901234567890123456789",
                        false,
                        20,
                        20
                ),
                NOW
        ).toCompletableFuture().join();

        assertEquals(15, receivedQuery.get().relevanceQuery().length());
        assertEquals(12, context.memory().items().stream()
                .mapToInt(item -> item.content().length())
                .sum());
        assertEquals(1, context.recentConversation().size());
        assertEquals(10, context.recentConversation().getFirst().text().length());
        assertEquals(ConversationTurnSnapshot.Role.PERSON,
                context.recentConversation().getFirst().role());
    }

}
