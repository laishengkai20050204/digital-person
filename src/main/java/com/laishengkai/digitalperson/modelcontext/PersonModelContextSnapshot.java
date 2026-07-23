package com.laishengkai.digitalperson.modelcontext;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentitySnapshot;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.ActiveStateEffectSnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;

import java.util.List;
import java.util.Objects;

/** Common provider-neutral background shared by state and activity model requests. */
public record PersonModelContextSnapshot(
        PersonId personId,
        PersonIdentitySnapshot identity,
        PersonalitySnapshot personality,
        PersonStateSnapshot currentState,
        List<ActiveStateEffectSnapshot> activeEffects,
        List<PersonEventSnapshot> activeEvents,
        List<PersonEventSnapshot> recentEvents,
        PersonMemoryContext memory,
        List<ConversationTurnSnapshot> recentConversation,
        TemporalContextSnapshot temporal
) {
    public PersonModelContextSnapshot {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        identity = Objects.requireNonNull(identity, "identity cannot be null");
        personality = Objects.requireNonNull(personality, "personality cannot be null");
        currentState = Objects.requireNonNull(currentState, "currentState cannot be null");
        activeEffects = immutable(activeEffects, "activeEffects");
        activeEvents = immutable(activeEvents, "activeEvents");
        recentEvents = immutable(recentEvents, "recentEvents");
        memory = Objects.requireNonNull(memory, "memory cannot be null");
        recentConversation = immutable(recentConversation, "recentConversation");
        temporal = Objects.requireNonNull(temporal, "temporal cannot be null");
    }

    private static <T> List<T> immutable(List<T> values, String fieldName) {
        List<T> copied = List.copyOf(Objects.requireNonNull(
                values,
                fieldName + " cannot be null"
        ));
        if (copied.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(fieldName + " cannot contain null");
        }
        return copied;
    }
}
