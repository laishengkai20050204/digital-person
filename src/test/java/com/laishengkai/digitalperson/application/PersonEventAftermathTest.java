package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.AftermathStateEffectPlan;
import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.StateDimension;
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
    void finishedCommunicationLeavesIndependentAftermathDuringLaterMusic() {
        Person person = new Person(new Personality(0.5, 0.7, 0.4, 0.8, 0.6, 0.9));
        Repository repository = new Repository(person);
        EventStateImpactEvaluator evaluator = context -> {
            if (context.newEvent().activityType().equals("CHAT")) {
                return CompletableFuture.completedFuture(new EventStateImpact(
                        List.of(new StateTransition(StateDimension.TENSION, 0.4)),
                        new AftermathStateEffectPlan(
                                Duration.ofHours(6),
                                List.of(
                                        new StateTransition(StateDimension.VALENCE, -0.9),
                                        new StateTransition(StateDimension.LONELINESS, 0.8)
                                )
                        )
                ));
            }
            return CompletableFuture.completedFuture(EventStateImpact.none());
        };
        PersonEventCommandService service = new PersonEventCommandService(
                repository,
                new StateUpdater(),
                evaluator
        );

        PersonEvent communication = openEvent(ActivityType.CHAT, "major relationship loss", START);
        service.start(person.getId(), communication, START).toCompletableFuture().join();

        Instant finishTime = START.plus(Duration.ofMinutes(10));
        PersonEventCommandResult finished = service.finish(
                person.getId(),
                communication.getId(),
                EventEndReason.COMPLETED,
                finishTime
        ).toCompletableFuture().join();

        assertFalse(finished.stateEvolutionContext().channelEffects()
                .containsKey(ActivityChannel.COMMUNICATION));
        assertEquals(1, finished.stateEvolutionContext().residualEffects().size());
        assertTrue(finished.stateEvolutionContext().residualEffects()
                .containsKey(communication.getId()));

        Instant musicTime = finishTime.plus(Duration.ofMinutes(10));
        PersonEvent music = openEvent(ActivityType.LISTEN_MUSIC, "listen to music", musicTime);
        PersonEventCommandResult later = service.start(
                person.getId(),
                music,
                musicTime
        ).toCompletableFuture().join();

        assertTrue(later.stateEvolutionContext().channelEffects()
                .containsKey(ActivityChannel.AUDIO));
        assertFalse(later.stateEvolutionContext().channelEffects()
                .containsKey(ActivityChannel.COMMUNICATION));
        assertEquals(1, later.stateEvolutionContext().residualEffects().size());
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
