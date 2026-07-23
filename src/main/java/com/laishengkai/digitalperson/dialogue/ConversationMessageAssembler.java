package com.laishengkai.digitalperson.dialogue;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds true role-preserving chat history for dialogue-generation requests. */
public final class ConversationMessageAssembler {
    private ConversationMessageAssembler() {
    }

    /**
     * Builds system + prior role-preserving turns + current user message.
     * The supplied history must exclude the current user message.
     */
    public static List<ModelMessage> assemble(
            String systemMessage,
            List<ConversationTurnSnapshot> recentConversation,
            String currentUserMessage
    ) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new SystemModelMessage(systemMessage));
        for (ConversationTurnSnapshot turn : List.copyOf(Objects.requireNonNull(
                recentConversation,
                "recentConversation cannot be null"
        ))) {
            Objects.requireNonNull(turn, "recentConversation cannot contain null");
            messages.add(switch (turn.role()) {
                case USER -> new UserModelMessage(turn.text());
                case PERSON -> AssistantModelMessage.text(turn.text());
                case SYSTEM -> new SystemModelMessage(turn.text());
            });
        }
        messages.add(new UserModelMessage(currentUserMessage));
        return List.copyOf(messages);
    }
}
