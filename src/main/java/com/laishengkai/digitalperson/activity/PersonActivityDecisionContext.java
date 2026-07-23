package com.laishengkai.digitalperson.activity;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentitySnapshot;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.ActiveStateEffectSnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;

import java.time.Instant;
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
        evaluationTime = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
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
