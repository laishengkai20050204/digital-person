package com.laishengkai.digitalperson.activity;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.modelcontext.TemporalContextSnapshot;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentitySnapshot;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.ActiveStateEffectSnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** Provider-neutral context for one autonomous digital-person activity decision. */
public record PersonActivityDecisionContext(
        PersonId personId,
        PersonIdentitySnapshot identity,
        PersonalitySnapshot personality,
        PersonStateSnapshot currentState,
        List<ActiveStateEffectSnapshot> activeEffects,
        List<PersonEventSnapshot> activeEvents,
        List<PersonEventSnapshot> recentEvents,
        PersonMemoryContext memory,
        List<ConversationTurnSnapshot> recentConversation,
        String observation,
        TemporalContextSnapshot temporal,
        Instant evaluationTime
) {
    public PersonActivityDecisionContext {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        identity = Objects.requireNonNull(identity, "identity cannot be null");
        personality = Objects.requireNonNull(personality, "personality cannot be null");
        currentState = Objects.requireNonNull(currentState, "currentState cannot be null");
        activeEffects = immutable(activeEffects, "activeEffects");
        activeEvents = immutable(activeEvents, "activeEvents");
        recentEvents = immutable(recentEvents, "recentEvents");
        memory = Objects.requireNonNull(memory, "memory cannot be null");
        recentConversation = immutable(recentConversation, "recentConversation");
        observation = Objects.requireNonNullElse(observation, "").strip();
        temporal = Objects.requireNonNull(temporal, "temporal cannot be null");
        evaluationTime = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        if (!temporal.now().equals(evaluationTime)) {
            throw new IllegalArgumentException(
                    "temporal.now must equal evaluationTime"
            );
        }
        if (!temporal.timeZone().equals(identity.timeZone())) {
            throw new IllegalArgumentException(
                    "temporal.timeZone must equal identity.timeZone"
            );
        }
    }

    /** Compatibility constructor for callers that have not yet supplied temporal data. */
    public PersonActivityDecisionContext(
            PersonId personId,
            PersonIdentitySnapshot identity,
            PersonalitySnapshot personality,
            PersonStateSnapshot currentState,
            List<ActiveStateEffectSnapshot> activeEffects,
            List<PersonEventSnapshot> activeEvents,
            List<PersonEventSnapshot> recentEvents,
            PersonMemoryContext memory,
            List<ConversationTurnSnapshot> recentConversation,
            String observation,
            Instant evaluationTime
    ) {
        this(
                personId,
                identity,
                personality,
                currentState,
                activeEffects,
                activeEvents,
                recentEvents,
                memory,
                recentConversation,
                observation,
                TemporalContextSnapshot.from(
                        ZoneId.of(identity.timeZone()),
                        evaluationTime
                ),
                evaluationTime
        );
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, name + " cannot be null"));
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(name + " cannot contain null");
        }
        return copy;
    }
}
