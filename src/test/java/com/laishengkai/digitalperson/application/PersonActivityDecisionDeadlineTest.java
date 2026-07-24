package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionContext;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionModel;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonActivityDecisionDeadlineTest {
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-07-23T12:08:00Z");

    @Test
    void aModelResponseAfterTheDeadlineCannotBePersisted() {
        PersonRepository repository = mock(PersonRepository.class);
        PersonActivityDecisionModel model = mock(PersonActivityDecisionModel.class);
        PersonActivityDecisionContextAssembler activityAssembler = mock(
                PersonActivityDecisionContextAssembler.class
        );
        EventStateImpactEvaluator effectEvaluator = mock(EventStateImpactEvaluator.class);
        StateEvaluationContextAssembler effectAssembler = mock(
                StateEvaluationContextAssembler.class
        );
        MutableClock clock = new MutableClock(NOW);
        Person person = new Person(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        PersonId personId = person.getId();
        CompletableFuture<PersonActivityDecisionPlan> modelResult = new CompletableFuture<>();

        when(repository.findById(personId)).thenReturn(Optional.of(
                new VersionedPerson(person, 0)
        ));
        when(activityAssembler.assemble(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        mock(PersonActivityDecisionContext.class)
                ));
        when(model.decide(any())).thenReturn(modelResult);

        PersonActivityDecisionService service = new PersonActivityDecisionService(
                repository,
                new StateUpdater(),
                model,
                activityAssembler,
                effectEvaluator,
                effectAssembler,
                clock
        );
        CompletableFuture<PersonActivityDecisionResult> decision = service.decide(
                personId,
                NOW,
                DEADLINE
        ).toCompletableFuture();

        clock.set(DEADLINE);
        modelResult.complete(PersonActivityDecisionPlan.unchanged(30));

        CompletionException failure = assertThrows(CompletionException.class, decision::join);
        assertInstanceOf(
                PersonActivityDecisionDeadlineExceededException.class,
                unwrap(failure)
        );
        verify(repository, never()).save(any(), anyLong());
    }


    @Test
    void aModelThatNeverReturnsIsCancelledAtTheDeadline() {
        PersonRepository repository = mock(PersonRepository.class);
        PersonActivityDecisionModel model = mock(PersonActivityDecisionModel.class);
        PersonActivityDecisionContextAssembler activityAssembler = mock(
                PersonActivityDecisionContextAssembler.class
        );
        EventStateImpactEvaluator effectEvaluator = mock(EventStateImpactEvaluator.class);
        StateEvaluationContextAssembler effectAssembler = mock(
                StateEvaluationContextAssembler.class
        );
        Person person = new Person(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        PersonId personId = person.getId();
        CompletableFuture<PersonActivityDecisionPlan> modelResult = new CompletableFuture<>();
        Instant decisionTime = Instant.now();
        Instant deadline = decisionTime.plus(Duration.ofMillis(250));

        when(repository.findById(personId)).thenReturn(Optional.of(
                new VersionedPerson(person, 0)
        ));
        when(activityAssembler.assemble(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        mock(PersonActivityDecisionContext.class)
                ));
        when(model.decide(any())).thenReturn(modelResult);

        PersonActivityDecisionService service = new PersonActivityDecisionService(
                repository,
                new StateUpdater(),
                model,
                activityAssembler,
                effectEvaluator,
                effectAssembler,
                Clock.systemUTC()
        );

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> service.decide(personId, decisionTime, deadline)
                        .toCompletableFuture()
                        .join()
        );
        assertInstanceOf(
                PersonActivityDecisionDeadlineExceededException.class,
                unwrap(failure)
        );
        assertTrue(modelResult.isCancelled());
        verify(repository, never()).save(any(), anyLong());
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        private void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
