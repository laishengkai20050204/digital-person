package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.FinishActivityCommand;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;

import java.time.Instant;
import java.util.Objects;

/** Validates all activity commands before the aggregate timeline is mutated. */
final class PersonActivityPlanValidator {

    void validate(Person person, PersonActivityDecisionPlan plan, Instant now) {
        Person safePerson = Objects.requireNonNull(person, "person cannot be null");
        PersonActivityDecisionPlan safePlan = Objects.requireNonNull(
                plan,
                "plan cannot be null"
        );
        Instant evaluationTime = Objects.requireNonNull(now, "now cannot be null");
        for (FinishActivityCommand finish : safePlan.finishCommands()) {
            PersonEvent event = safePerson.getPersonEventById(finish.eventId())
                    .orElseThrow(() -> new InvalidPersonActivityDecisionException(
                            "FINISH references an unknown person event: "
                                    + finish.eventId()
                    ));
            if (!event.isOpen() || !event.contains(evaluationTime)) {
                throw new InvalidPersonActivityDecisionException(
                        "FINISH references an event that is not active: "
                                + finish.eventId()
                );
            }
        }
    }
}
