package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.ChannelStateEffect;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransition;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPersonRepositoryMySqlTest {
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("digital_person")
            .withUsername("digital_person")
            .withPassword("digital_person_test");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcPersonRepository repository;

    @BeforeAll
    static void startPersistenceAdapter() {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(MYSQL.getJdbcUrl());
        hikari.setUsername(MYSQL.getUsername());
        hikari.setPassword(MYSQL.getPassword());
        hikari.setMaximumPoolSize(4);
        hikari.setPoolName("person-repository-test");
        dataSource = new HikariDataSource(hikari);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/mysql")
                .validateMigrationNaming(true)
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcPersonRepository(
                jdbcTemplate,
                new PersonAggregateJsonMapper(
                        JsonMapper.builder()
                                .addModule(new JavaTimeModule())
                                .build()
                )
        );
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void clearRows() {
        jdbcTemplate.update("DELETE FROM digital_person");
    }

    @Test
    void insertsAndRestoresCompleteAggregateAtVersionZero() {
        Person person = personWithActiveEvent();

        assertTrue(repository.insert(person));
        assertFalse(repository.insert(person));

        VersionedPerson loaded = repository.findById(person.getId()).orElseThrow();
        assertEquals(0L, loaded.version());
        assertEquals(person.getId(), loaded.person().getId());
        assertEquals(person.getPersonality(), loaded.person().getPersonality());
        assertEquals(person.getStateSnapshot(), loaded.person().getStateSnapshot());
        assertEquals(
                person.getStateEvolutionContext(),
                loaded.person().getStateEvolutionContext()
        );
        assertEquals(1, loaded.person().getPersonTimeline().getAll().size());
    }

    @Test
    void staleVersionCannotOverwriteACommittedAggregate() {
        Person person = new Person(neutralPersonality());
        assertTrue(repository.insert(person));

        VersionedPerson first = repository.findById(person.getId()).orElseThrow();
        VersionedPerson stale = repository.findById(person.getId()).orElseThrow();
        first.person().recordUserEvent(
                historicalEvent("第一次提交", NOW.minusSeconds(120), NOW.minusSeconds(60)),
                NOW
        );
        stale.person().recordUserEvent(
                historicalEvent("过期提交", NOW.minusSeconds(240), NOW.minusSeconds(180)),
                NOW
        );

        assertTrue(repository.save(first.person(), first.version()));
        assertFalse(repository.save(stale.person(), stale.version()));

        VersionedPerson loaded = repository.findById(person.getId()).orElseThrow();
        assertEquals(1L, loaded.version());
        assertEquals(1, loaded.person().getUserTimeline().getAll().size());
        assertEquals(
                "第一次提交",
                loaded.person().getUserTimeline().getAll().getFirst().getTitle()
        );
    }

    @Test
    void concurrentCompareAndSetAllowsExactlyOneWinner() throws Exception {
        Person person = new Person(neutralPersonality());
        assertTrue(repository.insert(person));
        VersionedPerson left = repository.findById(person.getId()).orElseThrow();
        VersionedPerson right = repository.findById(person.getId()).orElseThrow();
        left.person().recordUserEvent(
                historicalEvent("left", NOW.minusSeconds(300), NOW.minusSeconds(240)),
                NOW
        );
        right.person().recordUserEvent(
                historicalEvent("right", NOW.minusSeconds(180), NOW.minusSeconds(120)),
                NOW
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> leftResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                return repository.save(left.person(), left.version());
            });
            Future<Boolean> rightResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                return repository.save(right.person(), right.version());
            });
            ready.await();
            start.countDown();

            int winners = (leftResult.get() ? 1 : 0) + (rightResult.get() ? 1 : 0);
            assertEquals(1, winners);
        }

        VersionedPerson loaded = repository.findById(person.getId()).orElseThrow();
        assertEquals(1L, loaded.version());
        assertEquals(1, loaded.person().getUserTimeline().getAll().size());
    }

    @Test
    void aNewRepositoryInstanceReadsPreviouslyCommittedData() {
        Person person = personWithActiveEvent();
        assertTrue(repository.insert(person));

        JdbcPersonRepository restartedRepository = new JdbcPersonRepository(
                new JdbcTemplate(dataSource),
                new PersonAggregateJsonMapper(
                        JsonMapper.builder()
                                .addModule(new JavaTimeModule())
                                .build()
                )
        );

        VersionedPerson loaded = restartedRepository.findById(person.getId()).orElseThrow();
        assertEquals(person.getId(), loaded.person().getId());
        assertEquals(person.getStateSnapshot(), loaded.person().getStateSnapshot());
        assertEquals(person.getStateEvolutionContext(), loaded.person().getStateEvolutionContext());
    }

    private static Person personWithActiveEvent() {
        Person person = new Person(neutralPersonality());
        PersonEvent music = new PersonEvent(
                ActivityType.LISTEN_MUSIC,
                "听音乐",
                "宿舍",
                TimeRange.openEnded(NOW.minusSeconds(600))
        );
        person.startPersonEvent(music, NOW.minusSeconds(600));
        person.commitStateUpdate(
                person.getState(),
                new StateEvolutionContext(
                        NOW.minusSeconds(600),
                        Map.of(
                                ActivityChannel.AUDIO,
                                new ChannelStateEffect(
                                        ActivityChannel.AUDIO,
                                        music.getId(),
                                        List.of(new StateTransition(
                                                StateDimension.TENSION,
                                                -0.3
                                        ))
                                )
                        )
                )
        );
        return person;
    }

    private static PersonEvent historicalEvent(
            String title,
            Instant start,
            Instant end
    ) {
        return new PersonEvent(
                EventId.random(),
                ActivityType.STUDY,
                title,
                "",
                TimeRange.closed(start, end),
                List.of(),
                ""
        );
    }

    private static Personality neutralPersonality() {
        return new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
    }
}
