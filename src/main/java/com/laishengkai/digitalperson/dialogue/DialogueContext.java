package com.laishengkai.digitalperson.dialogue;

import com.laishengkai.digitalperson.experience.LifeEvent;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.PersonState;

import java.util.List;
import java.util.Objects;

public record DialogueContext(
        String userMessage,
        Personality personality,
        PersonState state,
        List<LifeEvent> recentEvents
) {

    public DialogueContext {
        userMessage = Objects.requireNonNull(userMessage, "userMessage cannot be null").strip();
        personality = Objects.requireNonNull(personality, "personality cannot be null");
        state = Objects.requireNonNull(state, "state cannot be null");
        recentEvents = List.copyOf(
                Objects.requireNonNull(recentEvents, "recentEvents cannot be null")
        );
    }
}
