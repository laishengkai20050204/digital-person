package com.laishengkai.digitalperson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class Person {

    private final Personality personality;
    private final List<LifeEvent> lifeEvents = new ArrayList<>();

    public Person(Personality personality) {
        this.personality = Objects.requireNonNull(
                personality,
                "personality cannot be null"
        );
    }

    public Personality getPersonality() {
        return personality;
    }

    public List<LifeEvent> getLifeEvents() {
        return List.copyOf(lifeEvents);
    }

    public void addLifeEvent(LifeEvent lifeEvent) {
        lifeEvents.add(Objects.requireNonNull(
                lifeEvent,
                "lifeEvent cannot be null"
        ));
    }

    public CompletionStage<DialogueResult> chatAsync(String userMessage) {
        throw new UnsupportedOperationException("chatAsync is not implemented yet");
    }
}
