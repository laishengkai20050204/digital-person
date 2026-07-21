package com.laishengkai.digitalperson.person;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.EventTimeline;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.AffectState;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class Person {
    private final PersonId id;
    private final Personality personality;
    private PersonState state;
    private final EventTimeline personTimeline;
    private final EventTimeline userTimeline;
    private StateEvolutionContext stateEvolutionContext;

    public Person(Personality personality) {
        this(
                PersonId.random(),
                personality,
                new PersonState(new AffectState(0.0, 0.5, 0.0)),
                new EventTimeline(),
                new EventTimeline(),
                StateEvolutionContext.initial()
        );
    }

    public Person(Personality personality, PersonState state) {
        this(
                PersonId.random(),
                personality,
                state,
                new EventTimeline(),
                new EventTimeline(),
                StateEvolutionContext.initial()
        );
    }

    public Person(
            Personality personality,
            PersonState state,
            EventTimeline personTimeline,
            EventTimeline userTimeline
    ) {
        this(
                PersonId.random(),
                personality,
                state,
                personTimeline,
                userTimeline,
                StateEvolutionContext.initial()
        );
    }

    public Person(
            PersonId id,
            Personality personality,
            PersonState state,
            EventTimeline personTimeline,
            EventTimeline userTimeline,
            StateEvolutionContext stateEvolutionContext
    ) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.personality = Objects.requireNonNull(
                personality,
                "personality cannot be null"
        );
        this.state = Objects.requireNonNull(state, "state cannot be null").copy();
        this.personTimeline = Objects.requireNonNull(
                personTimeline,
                "personTimeline cannot be null"
        ).copy();
        this.userTimeline = Objects.requireNonNull(
                userTimeline,
                "userTimeline cannot be null"
        ).copy();
        this.stateEvolutionContext = Objects.requireNonNull(
                stateEvolutionContext,
                "stateEvolutionContext cannot be null"
        );
    }

    public PersonId getId() {
        return id;
    }

    public Personality getPersonality() {
        return personality;
    }

    public PersonState getState() {
        return state.copy();
    }

    public PersonStateSnapshot getStateSnapshot() {
        return state.snapshot();
    }

    public StateEvolutionContext getStateEvolutionContext() {
        return stateEvolutionContext;
    }

    public EventTimeline getPersonTimeline() {
        return personTimeline.copy();
    }

    public EventTimeline getUserTimeline() {
        return userTimeline.copy();
    }

    public List<PersonEvent> getCurrentPersonEvents(Instant now) {
        return personTimeline.getCurrentEvents(now);
    }

    public List<PersonEvent> getRecentPersonEvents(Instant now, Duration duration) {
        return personTimeline.getRecentEvents(now, duration);
    }

    public void startPersonEvent(PersonEvent event, Instant now) {
        personTimeline.start(event, now);
    }

    public void recordPersonEvent(PersonEvent event, Instant now) {
        personTimeline.record(event, now);
    }

    public void finishPersonEvent(
            EventId eventId,
            Instant endTime,
            EventEndReason reason,
            Instant now
    ) {
        personTimeline.finish(eventId, endTime, reason, now);
    }

    public void startUserEvent(PersonEvent event, Instant now) {
        userTimeline.start(event, now);
    }

    public void recordUserEvent(PersonEvent event, Instant now) {
        userTimeline.record(event, now);
    }

    public void finishUserEvent(
            EventId eventId,
            Instant endTime,
            EventEndReason reason,
            Instant now
    ) {
        userTimeline.finish(eventId, endTime, reason, now);
    }

    /**
     * @deprecated Dialogue orchestration belongs to an application service.
     */
    @Deprecated(forRemoval = true)
    public CompletionStage<DialogueResult> chatAsync(String userMessage) {
        throw new UnsupportedOperationException(
                "Use an application-layer chat service instead"
        );
    }

    public void commitStateUpdate(
            PersonState updatedState,
            StateEvolutionContext updatedContext
    ) {
        this.state = Objects.requireNonNull(
                updatedState,
                "updatedState cannot be null"
        ).copy();
        this.stateEvolutionContext = Objects.requireNonNull(
                updatedContext,
                "updatedContext cannot be null"
        );
    }

    @Override
    public String toString() {
        return "Person[id=" + id + "]";
    }
}
