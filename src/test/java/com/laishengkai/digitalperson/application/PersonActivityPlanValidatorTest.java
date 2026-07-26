package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.FinishActivityCommand;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.personality.Personality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonActivityPlanValidatorTest {
    private static final Instant NOW = Instant.parse("2026-07-26T08:00:00Z");

    @Test
    void acceptsAnActiveFinishTargetAndRejectsUnknownTargets() {
        Person person = Person.create(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        PersonEvent event = new PersonEvent(
                EventId.random(),
                ActivityType.STUDY,
                "学习",
                "宿舍",
                TimeRange.openEnded(NOW.minusSeconds(60)),
                List.of(),
                ""
        );
        person.startPersonEvent(event, NOW.minusSeconds(60));
        PersonActivityPlanValidator validator = new PersonActivityPlanValidator();

        assertDoesNotThrow(() -> validator.validate(
                person,
                new PersonActivityDecisionPlan(
                        List.of(new FinishActivityCommand(
                                event.getId(),
                                EventEndReason.COMPLETED
                        )),
                        30
                ),
                NOW
        ));
        assertThrows(InvalidPersonActivityDecisionException.class, () -> validator.validate(
                person,
                new PersonActivityDecisionPlan(
                        List.of(new FinishActivityCommand(
                                EventId.random(),
                                EventEndReason.COMPLETED
                        )),
                        30
                ),
                NOW
        ));
    }
}
