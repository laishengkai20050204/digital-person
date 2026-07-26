package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.FinishActivityCommand;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.activity.StartActivityCommand;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Applies an already model-produced plan to a copied aggregate timeline. */
final class PersonActivityPlanApplier {
    private final PersonActivityPlanValidator validator;

    PersonActivityPlanApplier() {
        this(new PersonActivityPlanValidator());
    }

    PersonActivityPlanApplier(PersonActivityPlanValidator validator) {
        this.validator = Objects.requireNonNull(
                validator,
                "validator cannot be null"
        );
    }

    AppliedActivityPlan apply(
            Person person,
            PersonActivityDecisionPlan plan,
            Instant now,
            Consumer<String> checkpoint
    ) {
        Consumer<String> guard = Objects.requireNonNull(
                checkpoint,
                "checkpoint cannot be null"
        );
        guard.accept("activity plan validation");
        validator.validate(person, plan, now);

        LinkedHashMap<EventId, PersonEvent> finishedEvents = new LinkedHashMap<>();
        for (FinishActivityCommand finish : plan.finishCommands()) {
            guard.accept("finish command application");
            person.finishPersonEvent(finish.eventId(), now, finish.reason(), now);
            finishedEvents.put(
                    finish.eventId(),
                    person.getPersonEventById(finish.eventId()).orElseThrow()
            );
        }

        List<PersonEvent> startedEvents = new ArrayList<>();
        for (StartActivityCommand start : plan.startCommands()) {
            guard.accept("start command application");
            List<PersonEvent> replacementCandidates = person.getCurrentPersonEvents(now)
                    .stream()
                    .filter(event -> event.getChannel() == start.channel())
                    .toList();
            PersonEvent event = new PersonEvent(
                    EventId.random(),
                    start.activityType(),
                    start.title(),
                    start.location(),
                    TimeRange.openEnded(now),
                    start.participants(),
                    start.notes()
            );
            try {
                person.startPersonEvent(event, now);
            } catch (IllegalArgumentException | IllegalStateException error) {
                throw new InvalidPersonActivityDecisionException(
                        "START conflicts with the current event timeline in channel "
                                + start.channel(),
                        error
                );
            }
            PersonEvent committedStart = person.getPersonEventById(
                    event.getId()
            ).orElseThrow();
            startedEvents.add(committedStart);
            replacementCandidates.forEach(replaced -> finishedEvents.put(
                    replaced.getId(),
                    person.getPersonEventById(replaced.getId()).orElseThrow()
            ));
        }

        return new AppliedActivityPlan(
                plan,
                startedEvents,
                List.copyOf(finishedEvents.values())
        );
    }
}
