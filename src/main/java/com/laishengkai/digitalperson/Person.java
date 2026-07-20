package com.laishengkai.digitalperson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class Person {

    private final Personality personality;
    private final List<LifeEvent> lifeEvents;

    public Person(Personality personality) {
        this.personality = Objects.requireNonNull(personality, "personality cannot be null");
        this.lifeEvents = new ArrayList<>();
    }

    public Personality getPersonality() {
        return personality;
    }

    public List<LifeEvent> getLifeEvents() {
        return Collections.unmodifiableList(lifeEvents);
    }

    public void addLifeEvent(LifeEvent lifeEvent) {
        lifeEvents.add(Objects.requireNonNull(lifeEvent, "lifeEvent cannot be null"));
    }

    /**
     * Starts an asynchronous dialogue turn.
     *
     * <p>The method returns immediately with a {@link CompletionStage}. The dialogue model may
     * perform a slow network request without blocking the caller's thread.
     */
    public CompletionStage<DialogueResult> chatAsync(
            String userMessage,
            DialogueModel dialogueModel
    ) {
        String normalizedMessage = Objects.requireNonNull(
                userMessage,
                "userMessage cannot be null"
        ).strip();
        if (normalizedMessage.isEmpty()) {
            throw new IllegalArgumentException("userMessage cannot be blank");
        }

        Objects.requireNonNull(dialogueModel, "dialogueModel cannot be null");

        return Objects.requireNonNull(
                dialogueModel.generateReply(this, normalizedMessage),
                "dialogueModel cannot return a null CompletionStage"
        );
    }
}
