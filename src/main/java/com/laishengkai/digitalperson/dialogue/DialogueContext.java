package com.laishengkai.digitalperson.dialogue;

import com.laishengkai.digitalperson.experience.EventTimeline;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.PersonState;

import java.util.Objects;

public record DialogueContext(
        String userMessage,
        Personality personality,
        PersonState state,
        EventTimeline personTimeline,
        EventTimeline userTimeline
) {
    public DialogueContext {
        userMessage = Objects.requireNonNull(
                userMessage,
                "userMessage cannot be null"
        ).strip();
        personality = Objects.requireNonNull(
                personality,
                "personality cannot be null"
        );
        state = Objects.requireNonNull(state, "state cannot be null").copy();
        personTimeline = Objects.requireNonNull(
                personTimeline,
                "personTimeline cannot be null"
        ).copy();
        userTimeline = Objects.requireNonNull(
                userTimeline,
                "userTimeline cannot be null"
        ).copy();
    }
}
