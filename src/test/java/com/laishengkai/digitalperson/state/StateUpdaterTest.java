package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateUpdaterTest {
    private static final double EPSILON = 1.0e-12;
    private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");

    @Test
    void preparesNewEventThenReusesCompletedEffect() {
        StateUpdater updater = new StateUpdater();
        PersonState state = stateWithHunger(0.7);
        PersonEvent eating = event(ActivityType.EAT, "吃饭");

        StateUpdatePreparation preparation = updater.prepare(
                state,
                List.of(eating),
                NOW,
                StateEvolutionContext.initial()
        );

        assertEquals(List.of(eating), preparation.eventsToEvaluate());

        StateEvolutionContext context = updater.complete(
                preparation,
                List.of(new ChannelStateEffect(
                        ActivityChannel.PRIMARY,
                        eating.getId(),
                        List.of(new StateTransition(StateDimension.HUNGER, -1.0))
                ))
        );

        StateUpdatePreparation next = updater.prepare(
                state,
                List.of(eating),
                NOW.plusSeconds(1800),
                context
        );

        assertTrue(next.eventsToEvaluate().isEmpty());
        assertEquals(
                0.7 * Math.exp(-0.5),
                state.getPhysicalState().getHunger(),
                EPSILON
        );
    }

    @Test
    void sameUpdaterCanServeIndependentPeople() {
        StateUpdater updater = new StateUpdater();
        PersonState first = stateWithHunger(0.7);
        PersonState second = stateWithHunger(0.4);
        PersonEvent eating = event(ActivityType.EAT, "吃饭");

        StateUpdatePreparation preparation = updater.prepare(
                first,
                List.of(eating),
                NOW,
                StateEvolutionContext.initial()
        );
        StateEvolutionContext firstContext = updater.complete(
                preparation,
                List.of(new ChannelStateEffect(
                        ActivityChannel.PRIMARY,
                        eating.getId(),
                        List.of(new StateTransition(StateDimension.HUNGER, -1.0))
                ))
        );

        updater.prepare(
                second,
                List.of(),
                NOW,
                StateEvolutionContext.initial()
        );
        updater.prepare(
                first,
                List.of(eating),
                NOW.plusSeconds(1800),
                firstContext
        );

        assertEquals(0.4, second.getPhysicalState().getHunger(), EPSILON);
    }

    @Test
    void completeRequiresExactPendingChannelsAndEventIds() {
        StateUpdater updater = new StateUpdater();
        PersonEvent eating = event(ActivityType.EAT, "吃饭");
        StateUpdatePreparation preparation = updater.prepare(
                PersonState.baseline(),
                List.of(eating),
                NOW,
                StateEvolutionContext.initial()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> updater.complete(preparation, List.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> updater.complete(
                        preparation,
                        List.of(new ChannelStateEffect(
                                ActivityChannel.PRIMARY,
                                event(ActivityType.EAT, "别的事件").getId(),
                                List.of()
                        ))
                )
        );
    }

    @Test
    void rejectsTimeBeforePreviousUpdate() {
        StateUpdater updater = new StateUpdater();
        StateEvolutionContext context = new StateEvolutionContext(NOW, java.util.Map.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> updater.prepare(
                        PersonState.baseline(),
                        List.of(),
                        NOW.minusSeconds(1),
                        context
                )
        );
    }

    private static PersonEvent event(ActivityType type, String title) {
        return new PersonEvent(
                type,
                title,
                "",
                TimeRange.openEnded(NOW)
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
}
