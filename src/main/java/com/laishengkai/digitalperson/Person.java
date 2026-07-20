package com.laishengkai.digitalperson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
}
