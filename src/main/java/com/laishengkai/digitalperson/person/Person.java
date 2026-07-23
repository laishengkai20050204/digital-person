package com.laishengkai.digitalperson.person;

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
import java.util.Optional;

/**
 * Aggregate root for one persistent digital person.
 *
 * <p>The aggregate owns stable identity and personality data, mutable short-term state and
 * two independent event timelines: one for the digital person and one for the
 * user. Mutable internals are never returned directly; callers receive defensive
 * copies or immutable snapshots.</p>
 *
 * <p>Infrastructure concerns such as LLM invocation, memory retrieval and
 * repository transactions are intentionally coordinated by application
 * services rather than stored inside this entity.</p>
 */
public final class Person {
    private final PersonId id;
    private final PersonIdentity identity;
    private final Personality personality;
    private PersonState state;
    private final EventTimeline personTimeline;
    private final EventTimeline userTimeline;
    private StateEvolutionContext stateEvolutionContext;

    /** Creates a new aggregate with an unspecified identity and baseline state. */
    public static Person create(Personality personality) {
        return create(PersonIdentity.unspecified(), personality);
    }

    /** Creates a new aggregate with a generated id and baseline state. */
    public static Person create(PersonIdentity identity, Personality personality) {
        return new Person(identity, personality);
    }

    /** Reconstitutes a complete aggregate from persistence with an unspecified identity. */
    public static Person reconstitute(
            PersonId id,
            Personality personality,
            PersonState state,
            EventTimeline personTimeline,
            EventTimeline userTimeline,
            StateEvolutionContext stateEvolutionContext
    ) {
        return reconstitute(
                id,
                PersonIdentity.unspecified(),
                personality,
                state,
                personTimeline,
                userTimeline,
                stateEvolutionContext
        );
    }

    /** Reconstitutes a complete aggregate from persistence. */
    public static Person reconstitute(
            PersonId id,
            PersonIdentity identity,
            Personality personality,
            PersonState state,
            EventTimeline personTimeline,
            EventTimeline userTimeline,
            StateEvolutionContext stateEvolutionContext
    ) {
        return new Person(
                id,
                identity,
                personality,
                state,
                personTimeline,
                userTimeline,
                stateEvolutionContext
        );
    }

    public Person(Personality personality) {
        this(PersonIdentity.unspecified(), personality);
    }

    public Person(PersonIdentity identity, Personality personality) {
        this(
                PersonId.random(),
                identity,
                personality,
                new PersonState(new AffectState(0.0, 0.5, 0.0)),
                new EventTimeline(),
                new EventTimeline(),
                StateEvolutionContext.initial()
        );
    }

    public Person(Personality personality, PersonState state) {
        this(PersonIdentity.unspecified(), personality, state);
    }

    public Person(
            PersonIdentity identity,
            Personality personality,
            PersonState state
    ) {
        this(
                PersonId.random(),
                identity,
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
                PersonIdentity.unspecified(),
                personality,
                state,
                personTimeline,
                userTimeline
        );
    }

    public Person(
            PersonIdentity identity,
            Personality personality,
            PersonState state,
            EventTimeline personTimeline,
            EventTimeline userTimeline
    ) {
        this(
                PersonId.random(),
                identity,
                personality,
                state,
                personTimeline,
                userTimeline,
                StateEvolutionContext.initial()
        );
    }

    /**
     * Compatibility constructor for persisted aggregates without stable identity data.
     * New persistence code should prefer {@link #reconstitute(PersonId, Personality,
     * PersonState, EventTimeline, EventTimeline, StateEvolutionContext)}.
     */
    public Person(
            PersonId id,
            Personality personality,
            PersonState state,
            EventTimeline personTimeline,
            EventTimeline userTimeline,
            StateEvolutionContext stateEvolutionContext
    ) {
        this(
                id,
                PersonIdentity.unspecified(),
                personality,
                state,
                personTimeline,
                userTimeline,
                stateEvolutionContext
        );
    }

    /**
     * Compatibility constructor for complete persisted aggregates. New persistence
     * code should prefer {@link #reconstitute(PersonId, PersonIdentity, Personality,
     * PersonState, EventTimeline, EventTimeline, StateEvolutionContext)}.
     */
    public Person(
            PersonId id,
            PersonIdentity identity,
            Personality personality,
            PersonState state,
            EventTimeline personTimeline,
            EventTimeline userTimeline,
            StateEvolutionContext stateEvolutionContext
    ) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.identity = Objects.requireNonNull(identity, "identity cannot be null");
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

    /** Returns a fully detached aggregate copy for repository and application boundaries. */
    public Person copy() {
        return reconstitute(
                id,
                identity,
                personality,
                state,
                personTimeline,
                userTimeline,
                stateEvolutionContext
        );
    }

    public PersonId getId() {
        return id;
    }

    public PersonIdentity getIdentity() {
        return identity;
    }

    public Personality getPersonality() {
        return personality;
    }

    /** Returns a detached mutable copy for controlled application workflows. */
    public PersonState getState() {
        return state.copy();
    }

    /** Returns the preferred read-only representation for prompts and APIs. */
    public PersonStateSnapshot getStateSnapshot() {
        return state.snapshot();
    }

    public StateEvolutionContext getStateEvolutionContext() {
        return stateEvolutionContext;
    }

    /** Returns a detached copy; mutations on it do not affect this aggregate. */
    public EventTimeline getPersonTimeline() {
        return personTimeline.copy();
    }

    /** Returns a detached copy; mutations on it do not affect this aggregate. */
    public EventTimeline getUserTimeline() {
        return userTimeline.copy();
    }

    /** Returns one detached person-owned event by identity. */
    public Optional<PersonEvent> getPersonEventById(EventId eventId) {
        return personTimeline.getById(eventId);
    }

    public List<PersonEvent> getCurrentPersonEvents(Instant now) {
        return personTimeline.getCurrentEvents(now);
    }

    public List<PersonEvent> getRecentPersonEvents(Instant now, Duration duration) {
        return personTimeline.getRecentEvents(now, duration);
    }

    public List<PersonEvent> getCurrentUserEvents(Instant now) {
        return userTimeline.getCurrentEvents(now);
    }

    public List<PersonEvent> getRecentUserEvents(Instant now, Duration duration) {
        return userTimeline.getRecentEvents(now, duration);
    }

    /** Starts an event experienced by the digital person. */
    public void startPersonEvent(PersonEvent event, Instant now) {
        personTimeline.start(event, now);
    }

    /** Records a completed event experienced by the digital person. */
    public void recordPersonEvent(PersonEvent event, Instant now) {
        personTimeline.record(event, now);
    }

    /** Finishes an active event experienced by the digital person. */
    public void finishPersonEvent(
            EventId eventId,
            Instant endTime,
            EventEndReason reason,
            Instant now
    ) {
        personTimeline.finish(eventId, endTime, reason, now);
    }

    /** Starts an event attributed to the user. */
    public void startUserEvent(PersonEvent event, Instant now) {
        userTimeline.start(event, now);
    }

    /** Records a completed event attributed to the user. */
    public void recordUserEvent(PersonEvent event, Instant now) {
        userTimeline.record(event, now);
    }

    /** Finishes an active event attributed to the user. */
    public void finishUserEvent(
            EventId eventId,
            Instant endTime,
            EventEndReason reason,
            Instant now
    ) {
        userTimeline.finish(eventId, endTime, reason, now);
    }

    /**
     * Atomically replaces the short-term state and its evolution context.
     *
     * <p>Application services should call this only after external evaluations
     * have completed and {@code StateUpdater.complete(...)} has validated the
     * resulting effects.</p>
     */
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
