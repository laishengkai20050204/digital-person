package com.laishengkai.digitalperson.dialogue;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ConversationMessageAssemblerTest {
    @Test
    void preservesConversationRolesAndAppendsCurrentUserMessage() {
        Instant now = Instant.parse("2026-07-23T14:20:00Z");

        List<ModelMessage> messages = ConversationMessageAssembler.assemble(
                "你是沈知夏。",
                List.of(
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.USER,
                                "我喉咙有点疼",
                                now.minusSeconds(60)
                        ),
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.PERSON,
                                "有没有发烧？",
                                now.minusSeconds(30)
                        )
                ),
                "暂时没有发烧"
        );

        assertEquals(4, messages.size());
        assertInstanceOf(SystemModelMessage.class, messages.get(0));
        assertEquals("我喉咙有点疼", assertInstanceOf(
                UserModelMessage.class,
                messages.get(1)
        ).text());
        assertEquals("有没有发烧？", assertInstanceOf(
                AssistantModelMessage.class,
                messages.get(2)
        ).text());
        assertEquals("暂时没有发烧", assertInstanceOf(
                UserModelMessage.class,
                messages.get(3)
        ).text());
    }
}
