package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonCurrentStateProjector;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentity;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshingWechatSlashCommandHandlerTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:20:00Z");

    @Test
    void productionConstructorExplicitlySelectsSpringAutowiring() throws Exception {
        var constructor = RefreshingWechatSlashCommandHandler.class.getConstructor(
                WechatSlashCommandService.class,
                ObjectProvider.class
        );

        assertThat(constructor.getAnnotation(Autowired.class)).isNotNull();
        assertThat(constructor.getParameterTypes())
                .containsExactly(WechatSlashCommandService.class, ObjectProvider.class);
    }

    @Test
    void flushesPendingActivityReviewBeforeReadingActivities() {
        Person person = person();
        AtomicBoolean refreshed = new AtomicBoolean();
        PersonRepository repository = repository(person, () -> {
            if (!refreshed.get()) {
                throw new AssertionError("activity repository was read before refresh completed");
            }
        });
        WechatSlashCommandService delegate = new WechatSlashCommandService(
                repository,
                new PersonCurrentStateProjector(new StateUpdater()),
                Clock.fixed(NOW, ZoneId.of("UTC"))
        );
        RefreshingWechatSlashCommandHandler handler =
                new RefreshingWechatSlashCommandHandler(
                        delegate,
                        personId -> {
                            refreshed.set(true);
                            return CompletableFuture.completedFuture(true);
                        },
                        Duration.ofSeconds(1)
                );

        String content = handler.handle(person.getId(), "#dp activity")
                .orElseThrow()
                .content();

        assertThat(refreshed).isTrue();
        assertThat(content).contains("当前活动（沈知夏）");
    }

    @Test
    void doesNotRefreshCommandsThatDoNotDisplayActivities() {
        Person person = person();
        AtomicInteger refreshes = new AtomicInteger();
        WechatSlashCommandService delegate = new WechatSlashCommandService(
                repository(person, () -> { }),
                new PersonCurrentStateProjector(new StateUpdater()),
                Clock.fixed(NOW, ZoneId.of("UTC"))
        );
        RefreshingWechatSlashCommandHandler handler =
                new RefreshingWechatSlashCommandHandler(
                        delegate,
                        personId -> {
                            refreshes.incrementAndGet();
                            return CompletableFuture.completedFuture(true);
                        },
                        Duration.ofSeconds(1)
                );

        handler.handle(person.getId(), "#dp state").orElseThrow();

        assertThat(refreshes).hasValue(0);
    }

    @Test
    void marksActivityOutputWhenRefreshTimesOut() {
        Person person = person();
        WechatSlashCommandService delegate = new WechatSlashCommandService(
                repository(person, () -> { }),
                new PersonCurrentStateProjector(new StateUpdater()),
                Clock.fixed(NOW, ZoneId.of("UTC"))
        );
        RefreshingWechatSlashCommandHandler handler =
                new RefreshingWechatSlashCommandHandler(
                        delegate,
                        personId -> new CompletableFuture<>(),
                        Duration.ofMillis(1)
                );

        String content = handler.handle(person.getId(), "#dp activity24h")
                .orElseThrow()
                .content();

        assertThat(content).contains("活动判断仍在处理中");
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

    private static PersonRepository repository(Person person, Runnable beforeRead) {
        return new PersonRepository() {
            @Override
            public Optional<VersionedPerson> findById(PersonId personId) {
                beforeRead.run();
                return person.getId().equals(personId)
                        ? Optional.of(new VersionedPerson(person.copy(), 1L))
                        : Optional.empty();
            }

            @Override
            public boolean save(Person updated, long expectedVersion) {
                throw new AssertionError("commands must not save directly");
            }
        };
    }
}
