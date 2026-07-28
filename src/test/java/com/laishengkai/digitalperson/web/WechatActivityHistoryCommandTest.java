package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonCurrentStateProjector;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentity;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WechatActivityHistoryCommandTest {
    private static final Instant NOW = Instant.parse("2026-07-28T03:30:00Z");

    @Test
    void returnsOnlyActivitiesOverlappingThePreviousTwentyFourHours() {
        Person person = person();
        person.recordPersonEvent(new PersonEvent(
                ActivityType.SLEEP,
                "前一天的睡眠",
                "宿舍",
                TimeRange.closed(
                        NOW.minusSeconds(27L * 60L * 60L),
                        NOW.minusSeconds(25L * 60L * 60L)
                )
        ), NOW);
        person.recordPersonEvent(new PersonEvent(
                ActivityType.STUDY,
                "视觉传达课程作业",
                "教室",
                TimeRange.closed(
                        NOW.minusSeconds(3L * 60L * 60L),
                        NOW.minusSeconds(2L * 60L * 60L)
                )
        ), NOW);
        person.startPersonEvent(new PersonEvent(
                ActivityType.REST,
                "刷手机休息",
                "宿舍",
                TimeRange.openEnded(NOW.minusSeconds(30L * 60L))
        ), NOW);
        WechatSlashCommandService service = new WechatSlashCommandService(
                repository(person),
                new PersonCurrentStateProjector(new StateUpdater()),
                Clock.fixed(NOW, ZoneId.of("UTC"))
        );

        String content = service.handle(person.getId(), "#dp activity24h")
                .orElseThrow()
                .content();

        assertThat(content)
                .contains("过去24小时活动（沈知夏）")
                .contains("刷手机休息")
                .contains("结束：进行中")
                .contains("视觉传达课程作业")
                .contains("持续：1小时")
                .doesNotContain("前一天的睡眠");
        assertThat(content.indexOf("刷手机休息"))
                .isLessThan(content.indexOf("视觉传达课程作业"));
    }

    @Test
    void advertisesTheHistoryCommandInHelp() {
        Person person = person();
        WechatSlashCommandService service = new WechatSlashCommandService(
                repository(person),
                new PersonCurrentStateProjector(new StateUpdater()),
                Clock.fixed(NOW, ZoneId.of("UTC"))
        );

        assertThat(service.handle(person.getId(), "#dp help").orElseThrow().content())
                .contains("#dp activity24h");
    }

    private static Person person() {
        return Person.create(
                new PersonIdentity(
                        "沈知夏",
                        null,
                        "女",
                        "上海",
                        ZoneId.of("Asia/Shanghai"),
                        Locale.SIMPLIFIED_CHINESE,
                        List.of("大学生"),
                        ""
                ),
                new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9)
        );
    }

    private static PersonRepository repository(Person person) {
        return new PersonRepository() {
            @Override
            public Optional<VersionedPerson> findById(PersonId personId) {
                return person.getId().equals(personId)
                        ? Optional.of(new VersionedPerson(person.copy(), 1L))
                        : Optional.empty();
            }

            @Override
            public boolean save(Person updated, long expectedVersion) {
                throw new AssertionError("history commands must remain read-only");
            }
        };
    }
}
