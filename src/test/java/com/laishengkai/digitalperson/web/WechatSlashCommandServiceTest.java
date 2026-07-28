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

class WechatSlashCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-28T03:30:00Z");

    @Test
    void returnsCurrentActivityWithoutInvokingDialogue() {
        Person person = person();
        person.startPersonEvent(
                new PersonEvent(
                        ActivityType.STUDY,
                        "视觉传达课程作业",
                        "宿舍",
                        TimeRange.openEnded(NOW.minusSeconds(30L * 60L))
                ),
                NOW
        );
        WechatSlashCommandService service = service(person);

        var result = service.handle(person.getId(), "/activity");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().content())
                .contains("当前活动（沈知夏）")
                .contains("[主活动] 视觉传达课程作业")
                .contains("类型：学习")
                .contains("地点：宿舍")
                .contains("持续：30分钟");
    }

    @Test
    void returnsStateAndHelpForExactSlashCommands() {
        Person person = person();
        WechatSlashCommandService service = service(person);

        var state = service.handle(person.getId(), "/STATE");
        var help = service.handle(person.getId(), "/help");

        assertThat(state).isPresent();
        assertThat(state.orElseThrow().content())
                .contains("当前状态（沈知夏）")
                .contains("情绪：")
                .contains("精力：")
                .contains("社交需求：");
        assertThat(help).isPresent();
        assertThat(help.orElseThrow().content())
                .contains("/activity")
                .contains("/effects");
    }

    @Test
    void leavesOrdinaryMessagesForTheDialogueService() {
        Person person = person();
        WechatSlashCommandService service = service(person);

        assertThat(service.handle(person.getId(), "activity")).isEmpty();
        assertThat(service.handle(person.getId(), "你现在的/state是什么")).isEmpty();
    }

    @Test
    void reservesUnknownSlashCommandsInsteadOfSendingThemToTheModel() {
        Person person = person();
        WechatSlashCommandService service = service(person);

        var result = service.handle(person.getId(), "/unknown");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().content())
                .isEqualTo("未知指令：/unknown\n发送 /help 查看可用指令。");
    }

    private static WechatSlashCommandService service(Person person) {
        return new WechatSlashCommandService(
                repository(person),
                new PersonCurrentStateProjector(new StateUpdater()),
                Clock.fixed(NOW, ZoneId.of("UTC"))
        );
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
                throw new AssertionError("slash commands must remain read-only");
            }
        };
    }
}
