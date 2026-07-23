package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionContext;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionModel;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.activity.FinishActivityCommand;
import com.laishengkai.digitalperson.activity.StartActivityCommand;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.AffectState;
import com.laishengkai.digitalperson.state.CognitiveState;
import com.laishengkai.digitalperson.state.EffectId;
import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PhysicalState;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import com.laishengkai.digitalperson.state.SocialState;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectEndPolicy;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransition;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.laishengkai.digitalperson.support.StateEffectTestFixtures.eventBoundImpact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonActivityDecisionServiceTest {
    private static final Instant START = Instant.parse("2026-07-23T12:00:00Z");
    private static final Personality PERSONALITY = new Personality(
            0.6, 0.7, 0.4, 0.8, 0.6, 0.8
    );

    @Test
    void appliesFinishThenStartEvaluatesNewEventAndSavesOnce() {
        Person person = new Person(PERSONALITY, stateWithFatigue(0.2));
        PersonEvent study = openEvent(ActivityType.STUDY, "修改课程设计", START);
        person.startPersonEvent(study, START);
        RegisteredStateEffect studyFatigue = new RegisteredStateEffect(
                EffectId.random(),
                study.getId(),
                StateEffectType.PHYSICAL,
                "持续学习积累疲劳",
                START,
                StateEffectEndPolicy.EVENT_END,
                null,
                List.of(new StateTransition(StateDimension.FATIGUE, 1.0))
        );
        person.commitStateUpdate(
                person.getState(),
                new StateEvolutionContext(
                        START,
                        Map.of(studyFatigue.effectId(), studyFatigue),
                        Set.of(study.getId())
                )
        );
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        AtomicReference<PersonActivityDecisionContext> decisionContext = new AtomicReference<>();
        PersonActivityDecisionModel model = context -> {
            decisionContext.set(context);
            return CompletableFuture.completedFuture(new PersonActivityDecisionPlan(
                    List.of(
                            new FinishActivityCommand(
                                    study.getId(),
                                    EventEndReason.COMPLETED
                            ),
                            new StartActivityCommand(
                                    ActivityType.REST,
                                    "躺在床上休息",
                                    "宿舍",
                                    List.of(),
                                    "完成学习后短暂休息"
                            )
                    ),
                    20
            ));
        };
        AtomicInteger effectEvaluations = new AtomicInteger();
        EventStateImpactEvaluator effectEvaluator = context -> {
            effectEvaluations.incrementAndGet();
            assertEquals("REST", context.newEvent().activityType());
            assertTrue(context.activeEvents().stream().noneMatch(event ->
                    event.eventId().equals(study.getId().toString())
            ));
            return CompletableFuture.completedFuture(eventBoundImpact(
                    new StateTransition(StateDimension.ENERGY, 0.5)
            ));
        };
        PersonActivityDecisionService service = new PersonActivityDecisionService(
                repository,
                new StateUpdater(),
                model,
                effectEvaluator
        );
        Instant now = START.plusSeconds(600);

        PersonActivityDecisionResult result = service.decide(
                person.getId(),
                "课程作业已经完成",
                now
        ).toCompletableFuture().join();

        assertEquals(1, effectEvaluations.get());
        assertEquals(1, result.startedEvents().size());
        assertEquals(ActivityType.REST, result.startedEvents().getFirst().getActivityType());
        assertEquals(1, result.finishedEvents().size());
        assertEquals(study.getId(), result.finishedEvents().getFirst().getId());
        assertEquals(
                EventEndReason.COMPLETED,
                result.finishedEvents().getFirst().getEndReason().orElseThrow()
        );
        assertTrue(result.state().fatigue() > 0.2);
        assertEquals(1, result.stateEvolutionContext().effects().size());
        EventId restId = result.startedEvents().getFirst().getId();
        assertTrue(result.stateEvolutionContext().effects().values().stream()
                .allMatch(effect -> restId.equals(effect.sourceEventId())));
        assertTrue(result.stateEvolutionContext().evaluatedEventIds().contains(restId));
        assertFalse(result.stateEvolutionContext().evaluatedEventIds().contains(study.getId()));
        assertEquals(now.plusSeconds(1_200), result.nextReviewAt());
        assertEquals(1, repository.saveCount());
        assertEquals(1L, repository.current(person.getId()).version());
        assertEquals(1, decisionContext.get().activeEffects().size());
        assertEquals("课程作业已经完成", decisionContext.get().observation());
    }

    @Test
    void sameChannelStartWithoutExplicitFinishUsesReplacedSemantics() {
        Person person = new Person(PERSONALITY);
        PersonEvent study = openEvent(ActivityType.STUDY, "学习", START);
        person.startPersonEvent(study, START);
        person.commitStateUpdate(
                person.getState(),
                new StateEvolutionContext(START, Map.of(), Set.of(study.getId()))
        );
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        PersonActivityDecisionService service = new PersonActivityDecisionService(
                repository,
                new StateUpdater(),
                context -> CompletableFuture.completedFuture(
                        new PersonActivityDecisionPlan(
                                List.of(new StartActivityCommand(
                                        ActivityType.SLEEP,
                                        "上床睡觉",
                                        "宿舍",
                                        List.of(),
                                        "已经很困"
                                )),
                                120
                        )
                ),
                context -> CompletableFuture.completedFuture(EventStateImpact.none())
        );
        Instant now = START.plusSeconds(3_600);

        PersonActivityDecisionResult result = service.decide(person.getId(), now)
                .toCompletableFuture()
                .join();

        assertEquals(ActivityType.SLEEP, result.startedEvents().getFirst().getActivityType());
        assertEquals(study.getId(), result.finishedEvents().getFirst().getId());
        assertEquals(
                EventEndReason.REPLACED,
                result.finishedEvents().getFirst().getEndReason().orElseThrow()
        );
        assertEquals(now, result.finishedEvents().getFirst().getEndTime().orElseThrow());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void startsDifferentChannelsTogetherAndEvaluatesEveryNewEvent() {
        Person person = new Person(PERSONALITY);
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        AtomicInteger evaluations = new AtomicInteger();
        PersonActivityDecisionService service = new PersonActivityDecisionService(
                repository,
                new StateUpdater(),
                context -> CompletableFuture.completedFuture(
                        new PersonActivityDecisionPlan(
                                List.of(
                                        new StartActivityCommand(
                                                ActivityType.REST,
                                                "休息",
                                                "宿舍",
                                                List.of(),
                                                ""
                                        ),
                                        new StartActivityCommand(
                                                ActivityType.CHAT,
                                                "和恋人聊天",
                                                "微信",
                                                List.of("用户"),
                                                ""
                                        ),
                                        new StartActivityCommand(
                                                ActivityType.LISTEN_MUSIC,
                                                "听轻音乐",
                                                "宿舍",
                                                List.of(),
                                                ""
                                        )
                                ),
                                15
                        )
                ),
                context -> {
                    evaluations.incrementAndGet();
                    return CompletableFuture.completedFuture(EventStateImpact.none());
                }
        );

        PersonActivityDecisionResult result = service.decide(person.getId(), START)
                .toCompletableFuture()
                .join();

        assertEquals(3, evaluations.get());
        assertEquals(3, result.startedEvents().size());
        assertEquals(3, result.stateEvolutionContext().evaluatedEventIds().size());
        assertEquals(3, repository.current(person.getId())
                .person()
                .getCurrentPersonEvents(START)
                .size());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void pendingEventIsEvaluatedBeforeActivityModelReceivesContext() {
        Person person = new Person(PERSONALITY);
        PersonEvent rest = openEvent(ActivityType.REST, "休息", START);
        person.startPersonEvent(rest, START);
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        AtomicInteger effectEvaluations = new AtomicInteger();
        AtomicReference<PersonActivityDecisionContext> received = new AtomicReference<>();
        PersonActivityDecisionService service = new PersonActivityDecisionService(
                repository,
                new StateUpdater(),
                context -> {
                    received.set(context);
                    return CompletableFuture.completedFuture(
                            PersonActivityDecisionPlan.unchanged(30)
                    );
                },
                context -> {
                    effectEvaluations.incrementAndGet();
                    return CompletableFuture.completedFuture(eventBoundImpact(
                            new StateTransition(StateDimension.ENERGY, 0.2)
                    ));
                }
        );

        PersonActivityDecisionResult result = service.decide(
                person.getId(),
                START.plusSeconds(60)
        ).toCompletableFuture().join();

        assertEquals(1, effectEvaluations.get());
        assertEquals(1, received.get().activeEffects().size());
        assertEquals(rest.getId().toString(), received.get().activeEffects()
                .getFirst().sourceEventId());
        assertTrue(result.stateEvolutionContext().evaluatedEventIds().contains(rest.getId()));
        assertEquals(1, result.stateEvolutionContext().effects().size());
    }

    @Test
    void invalidFinishPlanFailsWithoutPersistingOrEvaluatingEffects() {
        Person person = new Person(PERSONALITY);
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        AtomicInteger effectEvaluations = new AtomicInteger();
        PersonActivityDecisionService service = new PersonActivityDecisionService(
                repository,
                new StateUpdater(),
                context -> CompletableFuture.completedFuture(
                        new PersonActivityDecisionPlan(
                                List.of(new FinishActivityCommand(
                                        EventId.random(),
                                        EventEndReason.COMPLETED
                                )),
                                15
                        )
                ),
                context -> {
                    effectEvaluations.incrementAndGet();
                    return CompletableFuture.completedFuture(EventStateImpact.none());
                }
        );

        CompletionException error = assertThrows(
                CompletionException.class,
                () -> service.decide(person.getId(), START)
                        .toCompletableFuture()
                        .join()
        );

        assertInstanceOf(InvalidPersonActivityDecisionException.class, error.getCause());
        assertEquals(0, effectEvaluations.get());
        assertEquals(0, repository.saveCount());
        assertEquals(0L, repository.current(person.getId()).version());
    }

    @Test
    void staleModelDecisionCannotOverwriteConcurrentAggregateChange() {
        Person person = new Person(PERSONALITY);
        VersionedInMemoryRepository repository = new VersionedInMemoryRepository(person);
        CompletableFuture<PersonActivityDecisionPlan> decision = new CompletableFuture<>();
        PersonActivityDecisionService service = new PersonActivityDecisionService(
                repository,
                new StateUpdater(),
                context -> decision,
                context -> CompletableFuture.completedFuture(EventStateImpact.none())
        );

        CompletableFuture<PersonActivityDecisionResult> pending = service.decide(
                person.getId(),
                START
        ).toCompletableFuture();
        repository.advanceVersion(person.getId());
        decision.complete(PersonActivityDecisionPlan.unchanged(60));

        CompletionException error = assertThrows(CompletionException.class, pending::join);
        assertInstanceOf(PersonVersionConflictException.class, error.getCause());
        assertEquals(1L, repository.current(person.getId()).version());
        assertEquals(0, repository.saveCount());
    }

    private static PersonEvent openEvent(
            ActivityType type,
            String title,
            Instant startTime
    ) {
        return new PersonEvent(type, title, "", TimeRange.openEnded(startTime));
    }

    private static PersonState stateWithFatigue(double fatigue) {
        return new PersonState(
                new AffectState(0.0, 0.5, 0.0),
                CognitiveState.baseline(),
                new PhysicalState(fatigue, 0.0, 0.5),
                SocialState.baseline()
        );
    }

    private static final class VersionedInMemoryRepository implements PersonRepository {
        private final Map<PersonId, StoredPerson> people = new HashMap<>();
        private int saveCount;

        private VersionedInMemoryRepository(Person person) {
            people.put(person.getId(), new StoredPerson(person.copy(), 0L));
        }

        @Override
        public synchronized Optional<VersionedPerson> findById(PersonId personId) {
            StoredPerson stored = people.get(personId);
            if (stored == null) {
                return Optional.empty();
            }
            return Optional.of(new VersionedPerson(stored.person.copy(), stored.version));
        }

        @Override
        public synchronized boolean save(Person person, long expectedVersion) {
            StoredPerson stored = people.get(person.getId());
            if (stored == null || stored.version != expectedVersion) {
                return false;
            }
            saveCount++;
            people.put(
                    person.getId(),
                    new StoredPerson(person.copy(), expectedVersion + 1)
            );
            return true;
        }

        private synchronized VersionedPerson current(PersonId personId) {
            StoredPerson stored = people.get(personId);
            return new VersionedPerson(stored.person.copy(), stored.version);
        }

        private synchronized void advanceVersion(PersonId personId) {
            StoredPerson stored = people.get(personId);
            people.put(
                    personId,
                    new StoredPerson(stored.person.copy(), stored.version + 1)
            );
        }

        private synchronized int saveCount() {
            return saveCount;
        }

        private record StoredPerson(Person person, long version) {
        }
    }
}
