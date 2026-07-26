package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.experience.PersonEvent;

import java.util.List;
import java.util.Objects;

/** Aggregate timeline changes produced by one validated activity plan. */
record AppliedActivityPlan(
        PersonActivityDecisionPlan plan,
        List<PersonEvent> startedEvents,
        List<PersonEvent> finishedEvents
) {
    AppliedActivityPlan {
        plan = Objects.requireNonNull(plan, "plan cannot be null");
        startedEvents = List.copyOf(startedEvents);
        finishedEvents = List.copyOf(finishedEvents);
    }
}
