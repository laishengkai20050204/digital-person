package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectDraft;
import com.laishengkai.digitalperson.state.StateEffectEndPolicy;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateTransition;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonEventAftermathTest {
    private static final Instant START = Instant.parse("2026-07-22T08:00:00Z");

    @Test
    void finishedCommunicationReleasesEventBoundEffectButKeepsFixedEffectsDuringMusic() {
        Person person = new Person(new Personality(0.5, 0.7, 0.4, 0.8, 0.6, 0.9));
        Repository repository = new Repository(person);
        EventStateImpactEvaluator evaluator = context -> {
            if (context.newEvent().activityType().equals("CHAT")) {
                return CompletableFuture.completedFuture(new EventStateImpact(List.of(
                        new StateEffectDraft(
                                StateEffectType.COGNITIVE,
                                "激烈沟通本身持续占用注意力",
                                List.of(new StateTransition(
                                        StateDimension.MENTAL_LOAD,
                                        0.5
                                )),
                                StateEffectEndPolicy.EVENT_END,
                                Duration.ZERO
                        ),
                        new StateEffectDraft(
                                StateEffectType.EMOTIONAL,
                                "恋人明确提出分手，引发关系丧失感",
                                List.of(
                                        new StateTransition(StateDimension.VALENCE, -0.9),
                                        new StateTransition(StateDimension.TENSION, 0.8)
                                ),
                                StateEffectEndPolicy.FIXED_TIME,
                                Duration.ofHours(6)
                        ),
                        new StateEffectDraft(
                                StateEffectType.SOCIAL,
                                "亲密关系突然中断，引发孤独感",
                                List.of(new StateTransition(
                                        StateDimension.LONELINESS,
                                        0.8
                                )),
                                StateEffectEndPolicy.FIXED_TIME,
                                Duration.ofHours(6)
                        )
                )));
            }
            return CompletableFuture.completedFuture(EventStateImpact.none());
        };
        PersonEventCommandService service = new PersonEventCommandService(
                repository,
                new StateUpdater(),
                evaluator
        );

        PersonEvent communication = openEvent(ActivityType.CHAT, "major relationship loss", START);
        PersonEventCommandResult started = service.start(
                person.getId(),
                communication,
                START
        ).toCompletableFuture().join();
        assertEquals(3, started.stateEvolutionContext().effects().size());

        Instant finishTime = START.plus(Duration.ofMinutes(10));
        PersonEventCommandResult finished = service.finish(
                person.getId(),
                communication.getId(),
                EventEndReason.COMPLETED,
                finishTime
        ).toCompletableFuture().join();

        assertEquals(2, finished.stateEvolutionContext().effects().size());
        assertFalse(finished.stateEvolutionContext().effects().values().stream()
                .anyMatch(effect -> effect.endPolicy() == StateEffectEndPolicy.EVENT_END));
        assertTrue(finished.stateEvolutionContext().effects().values().stream()
                .allMatch(effect -> effect.sourceEventId().equals(communication.getId())));
        assertTrue(finished.stateEvolutionContext().effects().values().stream()
                .anyMatch(effect -> effect.cause().contains("分手")));

        Instant musicTime = finishTime.plus(Duration.ofMinutes(10));
        PersonEvent music = openEvent(ActivityType.LISTEN_MUSIC, "listen to music", musicTime);
        PersonEventCommandResult later = service.start(
                person.getId(),
                music,
                musicTime
        ).toCompletableFuture().join();

        assertEquals(2, later.stateEvolutionContext().effects().size());
        assertTrue(later.stateEvolutionContext().evaluatedEventIds().contains(music.getId()));
        assertTrue(later.state().valence() < finished.state().valence());
        assertTrue(later.state().loneliness() > finished.state().loneliness());
    }

    private static PersonEvent openEvent(
            ActivityType type,
            String title,
            Instant startTime
    ) {
        return new PersonEvent(type, title, "", TimeRange.openEnded(startTime));
    }

    private static final class Repository implements PersonRepository {
        private final Map<PersonId, VersionedPerson> people = new HashMap<>();

        private Repository(Person person) {
            people.put(person.getId(), new VersionedPerson(person.copy(), 0L));
        }

        @Override
        public synchronized Optional<VersionedPerson> findById(PersonId personId) {
            VersionedPerson stored = people.get(personId);
            return stored == null
                    ? Optional.empty()
                    : Optional.of(new VersionedPerson(
                            stored.person().copy(),
                            stored.version()
                    ));
        }

        @Override
        public synchronized boolean save(Person person, long expectedVersion) {
            VersionedPerson stored = people.get(person.getId());
            if (stored == null || stored.version() != expectedVersion) {
                return false;
            }
            people.put(
                    person.getId(),
                    new VersionedPerson(person.copy(), expectedVersion + 1)
            );
            return true;
        }
    }
}
