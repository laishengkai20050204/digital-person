package com.laishengkai.digitalperson.activity;

import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonActivityDecisionPlanTest {

    @Test
    void separatesFinishAndStartCommandsAndKeepsReviewInterval() {
        FinishActivityCommand finish = new FinishActivityCommand(
                EventId.random(),
                EventEndReason.COMPLETED
        );
        StartActivityCommand start = new StartActivityCommand(
                ActivityType.REST,
                "躺在床上休息",
                "宿舍",
                List.of(),
                "完成学习后短暂休息"
        );

        PersonActivityDecisionPlan plan = new PersonActivityDecisionPlan(
                List.of(finish, start),
                20
        );

        assertEquals(List.of(finish), plan.finishCommands());
        assertEquals(List.of(start), plan.startCommands());
        assertEquals(20, plan.nextReviewMinutes());
    }

    @Test
    void rejectsDuplicateFinishAndMultipleStartsInOneChannel() {
        EventId eventId = EventId.random();
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersonActivityDecisionPlan(
                        List.of(
                                new FinishActivityCommand(
                                        eventId,
                                        EventEndReason.COMPLETED
                                ),
                                new FinishActivityCommand(
                                        eventId,
                                        EventEndReason.INTERRUPTED
                                )
                        ),
                        15
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new PersonActivityDecisionPlan(
                        List.of(
                                new StartActivityCommand(
                                        ActivityType.STUDY,
                                        "学习",
                                        "",
                                        List.of(),
                                        ""
                                ),
                                new StartActivityCommand(
                                        ActivityType.REST,
                                        "休息",
                                        "",
                                        List.of(),
                                        ""
                                )
                        ),
                        15
                )
        );
    }

    @Test
    void rejectsReplacedAndOutOfRangeReviewIntervals() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FinishActivityCommand(
                        EventId.random(),
                        EventEndReason.REPLACED
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PersonActivityDecisionPlan.unchanged(0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PersonActivityDecisionPlan.unchanged(361)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new StartActivityCommand(
                        ActivityType.CHAT,
                        "聊天",
                        "微信",
                        List.of(" "),
                        ""
                )
        );
    }
}
