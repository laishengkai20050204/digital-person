package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentitySnapshot;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Complete, preassembled and provider-neutral input for one state evaluation. */
public record StateEvaluationContext(
        PersonId personId,
        PersonIdentitySnapshot identity,
        PersonalitySnapshot personality,
        PersonStateSnapshot currentState,
        List<ActiveStateEffectSnapshot> activeEffects,
        PersonEventSnapshot newEvent,
        List<PersonEventSnapshot> activeEvents,
        List<PersonEventSnapshot> recentEvents,
        PersonMemoryContext memory,
        List<ConversationTurnSnapshot> recentConversation,
        Instant evaluationTime
) {
    public StateEvaluationContext {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        identity = Objects.requireNonNull(identity, "identity cannot be null");
        personality = Objects.requireNonNull(personality, "personality cannot be null");
        currentState = Objects.requireNonNull(currentState, "currentState cannot be null");
        activeEffects = copy(activeEffects, "activeEffects");
        newEvent = Objects.requireNonNull(newEvent, "newEvent cannot be null");
        activeEvents = copy(activeEvents, "activeEvents");
        recentEvents = copy(recentEvents, "recentEvents");
        memory = Objects.requireNonNull(memory, "memory cannot be null");
        recentConversation = copy(recentConversation, "recentConversation");
        evaluationTime = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
    }

    /** Compatibility constructor for callers that have not yet supplied identity/effects. */
    public StateEvaluationContext(
            PersonId personId,
            PersonalitySnapshot personality,
            PersonStateSnapshot currentState,
            PersonEventSnapshot newEvent,
            List<PersonEventSnapshot> activeEvents,
            List<PersonEventSnapshot> recentEvents,
            PersonMemoryContext memory,
            List<ConversationTurnSnapshot> recentConversation,
            Instant evaluationTime
    ) {
        this(
                personId,
                PersonIdentitySnapshot.unspecified(),
                personality,
                currentState,
                List.of(),
                newEvent,
                activeEvents,
                recentEvents,
                memory,
                recentConversation,
                evaluationTime
        );
    }

    private static <T> List<T> copy(List<T> values, String fieldName) {
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
