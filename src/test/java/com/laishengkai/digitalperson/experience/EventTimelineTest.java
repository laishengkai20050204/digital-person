package com.laishengkai.digitalperson.experience;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTimelineTest {

    private static final Instant TEN = Instant.parse("2026-07-21T02:00:00Z");

    @Test
    void startingEventReplacesEarlierOpenEventInSameChannel() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent study = openEvent(ActivityType.STUDY, "学习", TEN);
        PersonEvent game = openEvent(ActivityType.ENTERTAINMENT, "打游戏", TEN.plus(2, HOURS));

        timeline.start(study);
        timeline.start(game);

        assertEquals(TEN.plus(2, HOURS), study.getEndTime().orElseThrow());
        assertEquals(EventEndReason.REPLACED, study.getEndReason().orElseThrow());
        assertFalse(study.isOpen());
        assertTrue(game.isOpen());
    }

    @Test
    void differentChannelsCanRemainActiveTogether() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent eat = openEvent(ActivityType.EAT, "吃饭", TEN);
        PersonEvent chat = openEvent(ActivityType.CHAT, "聊天", TEN.plus(5, MINUTES));
        PersonEvent music = openEvent(ActivityType.LISTEN_MUSIC, "听音乐", TEN.plus(10, MINUTES));

        timeline.start(eat);
        timeline.start(chat);
        timeline.start(music);

        List<PersonEvent> current = timeline.getCurrentEvents(TEN.plus(15, MINUTES));
        assertEquals(3, current.size());
        assertTrue(current.containsAll(List.of(eat, chat, music)));
    }

    @Test
    void newChatReplacesOnlyPreviousChat() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent eat = openEvent(ActivityType.EAT, "吃饭", TEN);
        PersonEvent firstChat = openEvent(ActivityType.CHAT, "第一次聊天", TEN.plus(5, MINUTES));
        PersonEvent secondChat = openEvent(ActivityType.CHAT, "第二次聊天", TEN.plus(20, MINUTES));

        timeline.start(eat);
        timeline.start(firstChat);
        timeline.start(secondChat);

        assertTrue(eat.isOpen());
        assertFalse(firstChat.isOpen());
        assertEquals(EventEndReason.REPLACED, firstChat.getEndReason().orElseThrow());
        assertTrue(secondChat.isOpen());
    }

    @Test
    void rejectsOutOfOrderOpenEventInSameChannelWithoutMutatingTimeline() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent study = openEvent(ActivityType.STUDY, "学习", TEN);
        PersonEvent earlierSleep = openEvent(ActivityType.SLEEP, "睡觉", TEN.minus(1, HOURS));
        timeline.start(study);

        assertThrows(IllegalStateException.class, () -> timeline.start(earlierSleep));
        assertTrue(study.isOpen());
        assertEquals(1, timeline.getAll().size());
    }

    @Test
    void rejectsDuplicateEventId() {
        EventTimeline timeline = new EventTimeline();
        EventId id = EventId.random();
        PersonEvent first = new PersonEvent(
                id,
                ActivityType.STUDY,
                "学习",
                "图书馆",
                TimeRange.closed(TEN, TEN.plus(1, HOURS)),
                List.of(),
                ""
        );
        PersonEvent duplicate = new PersonEvent(
                id,
                ActivityType.CHAT,
                "聊天",
                "",
                TimeRange.closed(TEN, TEN.plus(30, MINUTES)),
                List.of(),
                ""
        );

        timeline.record(first);
        assertThrows(IllegalArgumentException.class, () -> timeline.record(duplicate));
    }

    @Test
    void distinguishesOverlapFromConflict() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent eat = closedEvent(ActivityType.EAT, "吃饭", TEN, TEN.plus(1, HOURS));
        PersonEvent chat = closedEvent(
                ActivityType.CHAT,
                "聊天",
                TEN.plus(10, MINUTES),
                TEN.plus(30, MINUTES)
        );

        timeline.record(eat);

        assertEquals(List.of(eat), timeline.findOverlappingEvents(chat));
        assertTrue(timeline.findConflictingEvents(chat).isEmpty());
    }

    @Test
    void cancelledPlannedEventNeverBecomesCurrent() {
        EventTimeline timeline = new EventTimeline();
        Instant tomorrow = TEN.plus(1, DAYS);
        PersonEvent classEvent = closedEvent(
                ActivityType.STUDY,
                "上课",
                tomorrow,
                tomorrow.plus(2, HOURS)
        );

        timeline.schedule(classEvent);
        timeline.cancel(classEvent.getId(), TEN.plus(1, HOURS));

        assertEquals(EventStatus.CANCELLED, classEvent.getStatusAt(tomorrow));
        assertFalse(classEvent.contains(tomorrow.plus(30, MINUTES)));
    }

    @Test
    void finishingOpenEventStoresExplicitReason() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent study = openEvent(ActivityType.STUDY, "学习", TEN);
        timeline.start(study);

        timeline.finish(study.getId(), TEN.plus(1, HOURS), EventEndReason.COMPLETED);

        assertEquals(EventStatus.FINISHED, study.getStatusAt(TEN.plus(1, HOURS)));
        assertEquals(EventEndReason.COMPLETED, study.getEndReason().orElseThrow());
    }

    @Test
    void recordMarksClosedEventCompletedByDefault() {
        EventTimeline timeline = new EventTimeline();
        PersonEvent event = closedEvent(
                ActivityType.EXERCISE,
                "跑步",
                TEN,
                TEN.plus(1, HOURS)
        );

        timeline.record(event);

        assertEquals(EventEndReason.COMPLETED, event.getEndReason().orElseThrow());
        assertEquals(EventStatus.FINISHED, event.getStatusAt(TEN.plus(1, HOURS)));
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
