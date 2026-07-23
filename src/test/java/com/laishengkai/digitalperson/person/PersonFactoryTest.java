package com.laishengkai.digitalperson.person;

import com.laishengkai.digitalperson.experience.EventTimeline;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.AffectState;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonFactoryTest {

    @Test
    void createsNewAggregateWithGeneratedIdAndBaselineState() {
        Personality personality = personality();
        PersonIdentity identity = PersonIdentity.unspecified();

        Person person = Person.create(identity, personality);

        assertNotNull(person.getId());
        assertEquals(identity, person.getIdentity());
        assertEquals(personality, person.getPersonality());
        assertEquals(0.5, person.getStateSnapshot().energy());
        assertTrue(person.getPersonTimeline().getAll().isEmpty());
        assertTrue(person.getUserTimeline().getAll().isEmpty());
    }

    @Test
    void reconstitutesDetachedAggregateWithStableIdentity() {
        PersonId id = PersonId.random();
        Personality personality = personality();
        PersonIdentity identity = PersonIdentity.unspecified();
        PersonState state = new PersonState(new AffectState(0.2, 0.7, 0.1));
        EventTimeline personTimeline = new EventTimeline();
        EventTimeline userTimeline = new EventTimeline();

        Person person = Person.reconstitute(
                id,
                identity,
                personality,
                state,
                personTimeline,
                userTimeline,
                StateEvolutionContext.initial()
        );

        assertEquals(id, person.getId());
        assertEquals(identity, person.getIdentity());
        assertEquals(0.2, person.getStateSnapshot().valence());
        assertEquals(0.7, person.getStateSnapshot().energy());
        assertNotSame(state, person.getState());
        assertNotSame(personTimeline, person.getPersonTimeline());
        assertNotSame(userTimeline, person.getUserTimeline());
    }

    private static Personality personality() {
        return new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
    }
}
