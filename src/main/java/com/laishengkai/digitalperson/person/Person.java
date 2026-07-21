package com.laishengkai.digitalperson.person;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.experience.EventTimeline;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.AffectState;
import com.laishengkai.digitalperson.state.PersonState;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

@Getter
@ToString
public final class Person {

    private final Personality personality;
    private final PersonState state;
    private final EventTimeline personTimeline;
    private final EventTimeline userTimeline;

    public Person(Personality personality) {
        this(
                personality,
                new PersonState(new AffectState(0.0, 0.5, 0.0)),
                new EventTimeline(),
                new EventTimeline()
        );
    }

    public Person(Personality personality, PersonState state) {
        this(
                personality,
                state,
                new EventTimeline(),
                new EventTimeline()
        );
    }

    public Person(
            Personality personality,
            PersonState state,
            EventTimeline personTimeline,
            EventTimeline userTimeline
    ) {
        this.personality = Objects.requireNonNull(
                personality,
                "personality cannot be null"
        );
        this.state = Objects.requireNonNull(
                state,
                "state cannot be null"
        );
        this.personTimeline = Objects.requireNonNull(
                personTimeline,
                "personTimeline cannot be null"
        );
        this.userTimeline = Objects.requireNonNull(
                userTimeline,
                "userTimeline cannot be null"
        );
    }

    public CompletionStage<DialogueResult> chatAsync(String userMessage) {
        throw new UnsupportedOperationException("chatAsync is not implemented yet");
    }
}
