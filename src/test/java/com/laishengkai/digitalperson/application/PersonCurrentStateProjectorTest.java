package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.EventTimeline;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PersonCurrentStateProjectorTest {

    @Test
    void advancesAWorkingCopyToTheDialogueTimeWithoutMutatingTheAggregate() {
        Instant previousUpdate = Instant.parse("2026-07-25T00:00:00Z");
        Instant now = previousUpdate.plusSeconds(6 * 60 * 60);
        Person person = Person.reconstitute(
                PersonId.random(),
                new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5),
                PersonState.baseline(),
                new EventTimeline(),
                new EventTimeline(),
                new StateEvolutionContext(previousUpdate, Map.of(), Set.of())
        );

        PersonCurrentStateProjector.Projection projection =
                new PersonCurrentStateProjector(new StateUpdater()).project(person, now);

        assertThat(projection.evolutionContext().lastUpdatedAt()).isEqualTo(now);
        assertThat(person.getStateEvolutionContext().lastUpdatedAt())
                .isEqualTo(previousUpdate);
        assertThat(person.getStateSnapshot()).isNotSameAs(projection.state());
    }
}
