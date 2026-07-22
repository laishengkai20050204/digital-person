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
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStateEvaluationContextAssemblerTest {
    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");

    @Test
    void assemblesPersonalityEventsMemoryConversationAndRuntimeTime() {
        Person person = new Person(new Personality(0.6, 0.8, 0.4, 0.7, 0.9, 0.5));
        PersonEvent study = event(ActivityType.STUDY, "Prepare exam", 600);
        PersonEvent userChat = event(ActivityType.CHAT, "User sent a message", 60);
        person.startPersonEvent(study, NOW);
        person.startUserEvent(userChat, NOW);

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
                study,
                NOW
        ).toCompletableFuture().join();

        assertEquals(person.getId(), context.personId());
        assertEquals(0.8, context.personality().emotionality());
        assertEquals(NOW, context.evaluationTime());
        assertEquals(1, context.memory().items().size());
        assertEquals(1, context.recentConversation().size());
        assertTrue(context.activeEvents().stream().anyMatch(
                event -> event.owner() == PersonEventSnapshot.Owner.PERSON
        ));
        assertTrue(context.activeEvents().stream().anyMatch(
                event -> event.owner() == PersonEventSnapshot.Owner.USER
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
}
