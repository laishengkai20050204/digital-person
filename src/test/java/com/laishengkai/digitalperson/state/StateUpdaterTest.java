package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventTimeline;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static java.time.temporal.ChronoUnit.HOURS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateUpdaterTest {

    private static final double EPSILON = 1.0e-9;
    private static final Instant TEN = Instant.parse("2026-07-21T02:00:00Z");

    private final StateUpdater updater = new StateUpdater();

    @Test
    void sleepRecoversEmotionalCognitiveAndPhysicalState() {
        PersonState state = new PersonState(
                new AffectState(-0.2, 0.2, 0.7),
                new CognitiveState(0.3, 0.8, 0.3),
                new PhysicalState(0.9, 0.8, 0.2),
                new SocialState(0.5, 0.5)
        );
        PersonEvent sleep = finishedEvent(
                ActivityType.SLEEP,
                "睡觉",
                TEN,
                TEN.plus(8, HOURS)
        );

        updater.applyFinishedEvent(state, sleep);

        assertEquals(-0.1, state.getAffectState().getValence(), EPSILON);
        assertEquals(0.8, state.getAffectState().getEnergy(), EPSILON);
        assertEquals(0.45, state.getAffectState().getTension(), EPSILON);
        assertEquals(0.6, state.getCognitiveState().getFocus(), EPSILON);
        assertEquals(0.4, state.getCognitiveState().getMentalLoad(), EPSILON);
        assertEquals(0.1, state.getPhysicalState().getFatigue(), EPSILON);
        assertEquals(0.0, state.getPhysicalState().getSleepiness(), EPSILON);
        assertEquals(0.4, state.getPhysicalState().getHunger(), EPSILON);
    }

    @Test
    void studyRaisesLoadFatigueAndTension() {
        PersonState state = PersonState.baseline();
        PersonEvent study = finishedEvent(
                ActivityType.STUDY,
                "学习",
                TEN,
                TEN.plus(4, HOURS)
        );

        updater.applyFinishedEvent(state, study);

        assertEquals(-0.05, state.getAffectState().getValence(), EPSILON);
        assertEquals(0.3, state.getAffectState().getEnergy(), EPSILON);
        assertEquals(0.2, state.getAffectState().getTension(), EPSILON);
        assertEquals(0.35, state.getCognitiveState().getFocus(), EPSILON);
        assertEquals(0.35, state.getCognitiveState().getMentalLoad(), EPSILON);
        assertEquals(0.25, state.getPhysicalState().getFatigue(), EPSILON);
    }

    @Test
    void chatReducesLonelinessAndSocialNeed() {
        PersonState state = new PersonState(
                new AffectState(0.0, 0.5, 0.3),
                CognitiveState.baseline(),
                PhysicalState.baseline(),
                new SocialState(0.8, 0.9)
        );
        PersonEvent chat = finishedEvent(
                ActivityType.CHAT,
                "聊天",
                TEN,
                TEN.plus(1, HOURS)
        );

        updater.applyFinishedEvent(state, chat);

        assertEquals(0.15, state.getAffectState().getValence(), EPSILON);
        assertEquals(0.4, state.getSocialState().getLoneliness(), EPSILON);
        assertEquals(0.6, state.getSocialState().getSocialNeed(), EPSILON);
    }

    @Test
    void stateChangesAreClampedToTheirValidRanges() {
        PersonState state = new PersonState(
                new AffectState(0.95, 0.8, 0.0),
                new CognitiveState(0.9, 0.0, 0.9),
                new PhysicalState(0.0, 0.0, 0.1),
                SocialState.baseline()
        );
        PersonEvent sleep = finishedEvent(
                ActivityType.SLEEP,
                "睡觉",
                TEN,
                TEN.plus(8, HOURS)
        );

        updater.applyFinishedEvent(state, sleep);

        assertEquals(1.0, state.getAffectState().getValence(), EPSILON);
        assertEquals(1.0, state.getAffectState().getEnergy(), EPSILON);
        assertEquals(1.0, state.getCognitiveState().getFocus(), EPSILON);
        assertEquals(0.0, state.getPhysicalState().getFatigue(), EPSILON);
        assertEquals(0.0, state.getPhysicalState().getSleepiness(), EPSILON);
    }

    @Test
    void rejectsEventThatHasNotFinished() {
        PersonState state = PersonState.baseline();
        PersonEvent ongoingStudy = new PersonEvent(
                ActivityType.STUDY,
                "正在学习",
                "",
                TimeRange.openEnded(TEN)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> updater.applyFinishedEvent(state, ongoingStudy)
        );
    }

    private static PersonEvent finishedEvent(
            ActivityType type,
            String title,
            Instant start,
            Instant end
    ) {
        PersonEvent event = new PersonEvent(
                type,
                title,
                "",
                TimeRange.closed(start, end)
        );
        EventTimeline timeline = new EventTimeline();
        timeline.record(event, end);
        return event;
    }
}
