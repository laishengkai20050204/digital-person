package com.laishengkai.digitalperson.experience;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTimelineTest {
    private static final Instant TEN = Instant.parse("2026-07-21T02:00:00Z");

    @Test
    void startingEventReplacesEarlierOpenEventWithoutMutatingCallerObject() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent study = openEvent(ActivityType.STUDY, "学习", TEN);
        PersonEvent game = openEvent(
                ActivityType.ENTERTAINMENT,
                "打游戏",
                TEN.plus(2, HOURS)
        );

        timeline.start(study, TEN);
        timeline.start(game, TEN.plus(2, HOURS));

        assertTrue(study.isOpen());

        PersonEvent storedStudy = timeline.getById(study.getId()).orElseThrow();
        assertEquals(TEN.plus(2, HOURS), storedStudy.getEndTime().orElseThrow());
        assertEquals(
                EventEndReason.REPLACED,
                storedStudy.getEndReason().orElseThrow()
        );
        assertFalse(storedStudy.isOpen());
    }

    @Test
    void returnedEventsAreDefensiveCopies() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent study = openEvent(ActivityType.STUDY, "学习", TEN);
        timeline.start(study, TEN);

        EventTimeline copiedTimeline = timeline.copy();
        copiedTimeline.finish(
                study.getId(),
                TEN.plus(1, HOURS),
                EventEndReason.COMPLETED,
                TEN.plus(1, HOURS)
        );

        assertTrue(timeline.getById(study.getId()).orElseThrow().isOpen());
        assertFalse(copiedTimeline.getById(study.getId()).orElseThrow().isOpen());
    }

    @Test
    void differentChannelsCanRemainActiveTogether() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent eat = openEvent(ActivityType.EAT, "吃饭", TEN);
        PersonEvent chat = openEvent(
                ActivityType.CHAT,
                "聊天",
                TEN.plus(5, MINUTES)
        );
        PersonEvent music = openEvent(
                ActivityType.LISTEN_MUSIC,
                "听音乐",
                TEN.plus(10, MINUTES)
        );

        timeline.start(eat, TEN);
        timeline.start(chat, TEN.plus(5, MINUTES));
        timeline.start(music, TEN.plus(10, MINUTES));

        assertEquals(3, timeline.getCurrentEvents(TEN.plus(15, MINUTES)).size());
    }

    @Test
    void rejectsFutureAndOutOfOrderEvents() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent futureStudy = openEvent(
                ActivityType.STUDY,
                "未来学习",
                TEN.plus(1, HOURS)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> timeline.start(futureStudy, TEN)
        );

        PersonEvent study = openEvent(ActivityType.STUDY, "学习", TEN);
        timeline.start(study, TEN);

        PersonEvent earlierSleep = openEvent(
                ActivityType.SLEEP,
                "睡觉",
                TEN.minus(1, HOURS)
        );
        assertThrows(
                IllegalStateException.class,
                () -> timeline.start(earlierSleep, TEN)
        );
    }

    @Test
    void recentEventsIncludesOverlappingAndOngoingEvents() {
        EventTimeline timeline = new EventTimeline();
        Instant now = TEN.plus(48, HOURS);

        PersonEvent crossingBoundary = closedEvent(
                ActivityType.SLEEP,
                "睡眠",
                now.minus(25, HOURS),
                now.minus(23, HOURS)
        );
        PersonEvent recentChat = closedEvent(
                ActivityType.CHAT,
                "聊天",
                now.minus(2, HOURS),
                now.minus(1, HOURS)
        );
        PersonEvent ongoingMusic = openEvent(
                ActivityType.LISTEN_MUSIC,
                "听音乐",
                now.minus(30, MINUTES)
        );

        timeline.record(crossingBoundary, now);
        timeline.record(recentChat, now);
        timeline.start(ongoingMusic, now);

        List<PersonEvent> recentEvents = timeline.getLast24Hours(now);

        assertEquals(3, recentEvents.size());
        assertTrue(recentEvents.contains(crossingBoundary));
        assertTrue(recentEvents.contains(recentChat));
        assertTrue(recentEvents.contains(ongoingMusic));
    }

    @Test
    void recentEventsRequiresPositiveDuration() {
        EventTimeline timeline = new EventTimeline();

        assertThrows(
                IllegalArgumentException.class,
                () -> timeline.getRecentEvents(TEN, Duration.ZERO)
        );
    }

    private static PersonEvent openEvent(
            ActivityType type,
            String title,
            Instant start
    ) {
        return new PersonEvent(type, title, "", TimeRange.openEnded(start));
    }

    private static PersonEvent closedEvent(
            ActivityType type,
            String title,
            Instant start,
            Instant end
    ) {
        return new PersonEvent(type, title, "", TimeRange.closed(start, end));
    }
}
