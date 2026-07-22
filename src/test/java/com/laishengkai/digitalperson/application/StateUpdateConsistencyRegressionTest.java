package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.AffectState;
import com.laishengkai.digitalperson.state.CognitiveState;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PhysicalState;
import com.laishengkai.digitalperson.state.SocialState;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateTransition;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for state consistency across event and asynchronous boundaries. */
class StateUpdateConsistencyRegressionTest {
    private static final double EPSILON = 1.0e-12;
    private static final Instant START = Instant.parse("2026-07-22T08:00:00Z");
    private static final Personality PERSONALITY = new Personality(
            0.5,
            0.5,
            0.5,
            0.5,
            0.5,
            0.5
    );

    @Test
    void completedEventEffectMustStopAtTheEventEndBoundary() {
        Person person = new Person(PERSONALITY, stateWithHunger(0.7));
        PersonEvent eating = event(ActivityType.EAT, "吃饭", START);
        person.startPersonEvent(eating, START);

        InMemoryRepository repository = new InMemoryRepository(person);
        UpdatePersonStateService service = new UpdatePersonStateService(
                repository,
                new StateUpdater(),
                context -> CompletableFuture.completedFuture(
                        List.of(new StateTransition(StateDimension.HUNGER, -1.0))
                )
        );

        service.update(person.getId(), START).toCompletableFuture().join();

        Instant eventEnd = START.plusSeconds(600);
        person.finishPersonEvent(
                eating.getId(),
                eventEnd,
                EventEndReason.COMPLETED,
                eventEnd
        );

        service.update(person.getId(), START.plusSeconds(1800))
                .toCompletableFuture()
                .join();

        double expectedAfterTenMinutes = 0.7 * Math.exp(-1.0 / 6.0);
        assertEquals(
                expectedAfterTenMinutes,
                person.getState().getPhysicalState().getHunger(),
                EPSILON,
                "a cached event effect must not continue after the event has ended"
        );
    }

    @Test
    void staleEvaluationMustNotOverwriteAReplacementEventCommittedLater() {
        Person person = new Person(PERSONALITY);
        PersonEvent eating = event(ActivityType.EAT, "吃饭", START);
        person.startPersonEvent(eating, START);

        CompletableFuture<List<StateTransition>> eatingEvaluation =
                new CompletableFuture<>();
        CompletableFuture<List<StateTransition>> restEvaluation =
                new CompletableFuture<>();

        InMemoryRepository repository = new InMemoryRepository(person);
        UpdatePersonStateService service = new UpdatePersonStateService(
                repository,
                new StateUpdater(),
                context -> switch (context.newEvent().activityType()) {
                    case "EAT" -> eatingEvaluation;
                    case "REST" -> restEvaluation;
                    default -> CompletableFuture.failedFuture(
                            new IllegalArgumentException(
                                    "unexpected activity type: "
                                            + context.newEvent().activityType()
                            )
                    );
                }
        );

        CompletionStage<StateUpdateResult> staleUpdate = service.update(
                person.getId(),
                START
        );

        Instant replacementTime = START.plusSeconds(1);
        PersonEvent resting = event(ActivityType.REST, "休息", replacementTime);
        person.startPersonEvent(resting, replacementTime);

        CompletionStage<StateUpdateResult> newerUpdate = service.update(
                person.getId(),
                replacementTime
        );
        restEvaluation.complete(
                List.of(new StateTransition(StateDimension.ENERGY, 1.0))
        );
        newerUpdate.toCompletableFuture().join();

        assertEquals(
                resting.getId(),
                person.getStateEvolutionContext()
                        .channelEffects()
                        .get(ActivityChannel.PRIMARY)
                        .eventId()
        );

        eatingEvaluation.complete(
                List.of(new StateTransition(StateDimension.HUNGER, -1.0))
        );
        staleUpdate.handle((result, error) -> null)
                .toCompletableFuture()
                .join();

        assertEquals(
                resting.getId(),
                person.getStateEvolutionContext()
                        .channelEffects()
                        .get(ActivityChannel.PRIMARY)
                        .eventId(),
                "an older asynchronous evaluation must not overwrite newer state"
        );
    }

    private static PersonEvent event(
            ActivityType activityType,
            String title,
            Instant startTime
    ) {
        return new PersonEvent(
                activityType,
                title,
                "",
                TimeRange.openEnded(startTime)
        );
    }

    private static PersonState stateWithHunger(double hunger) {
        return new PersonState(
                new AffectState(0.0, 0.5, 0.0),
                CognitiveState.baseline(),
                new PhysicalState(0.0, 0.0, hunger),
                SocialState.baseline()
        );
    }

    private static final class InMemoryRepository implements PersonRepository {
        private final Map<PersonId, Person> people = new HashMap<>();

        private InMemoryRepository(Person person) {
            people.put(person.getId(), person);
        }

        @Override
        public Optional<Person> findById(PersonId personId) {
            return Optional.ofNullable(people.get(personId));
        }

        @Override
        public void save(Person person) {
            people.put(person.getId(), person);
        }
    }
}
