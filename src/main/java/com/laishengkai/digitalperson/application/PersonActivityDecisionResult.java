package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Result of one atomic autonomous activity-decision cycle. */
public record PersonActivityDecisionResult(
        PersonId personId,
        PersonActivityDecisionPlan plan,
        List<PersonEvent> startedEvents,
        List<PersonEvent> finishedEvents,
        PersonStateSnapshot state,
        StateEvolutionContext stateEvolutionContext,
        Instant decisionTime,
        Instant nextReviewAt
) {
    public PersonActivityDecisionResult {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        plan = Objects.requireNonNull(plan, "plan cannot be null");
        startedEvents = copyEvents(startedEvents, "startedEvents");
        finishedEvents = copyEvents(finishedEvents, "finishedEvents");
        state = Objects.requireNonNull(state, "state cannot be null");
        stateEvolutionContext = Objects.requireNonNull(
                stateEvolutionContext,
                "stateEvolutionContext cannot be null"
        );
        decisionTime = Objects.requireNonNull(decisionTime, "decisionTime cannot be null");
        nextReviewAt = Objects.requireNonNull(nextReviewAt, "nextReviewAt cannot be null");
        if (!nextReviewAt.isAfter(decisionTime)) {
            throw new IllegalArgumentException("nextReviewAt must be after decisionTime");
        }
    }

    private static List<PersonEvent> copyEvents(List<PersonEvent> events, String name) {
        return List.copyOf(Objects.requireNonNull(events, name + " cannot be null"))
                .stream()
                .map(event -> Objects.requireNonNull(
                        event,
                        name + " cannot contain null"
                ).copy())
                .toList();
    }
}
