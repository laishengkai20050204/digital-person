package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.laishengkai.digitalperson.conversation.ConversationEpisodeDraft;
import com.laishengkai.digitalperson.conversation.ConversationEpisodeSnapshot;
import com.laishengkai.digitalperson.conversation.ConversationSummarySnapshot;
import com.laishengkai.digitalperson.conversation.ConversationSummaryWorkItem;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.personality.Personality;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcRecentConversationRepositoryMySqlTest {
    private static final Instant NOW = Instant.parse("2026-07-25T02:00:00Z");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("digital_person")
            .withUsername("digital_person")
            .withPassword("digital_person_test");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcPersonRepository personRepository;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void startPersistenceAdapters() {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(MYSQL.getJdbcUrl());
        hikari.setUsername(MYSQL.getUsername());
        hikari.setPassword(MYSQL.getPassword());
        hikari.setMaximumPoolSize(4);
        hikari.setPoolName("conversation-repository-test");
        dataSource = new HikariDataSource(hikari);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/mysql")
                .validateMigrationNaming(true)
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        personRepository = new JdbcPersonRepository(
                jdbcTemplate,
                new PersonAggregateJsonMapper(
                        JsonMapper.builder()
                                .addModule(new JavaTimeModule())
                                .build()
                )
        );
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
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
        jdbcTemplate.update("DELETE FROM person_conversation_turn");
        jdbcTemplate.update("DELETE FROM digital_person");
    }

    @Test
    void appendsAtomicallyReadsLatestInOrderAndSurvivesRepositoryRestart() {
        Person person = Person.create(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        assertTrue(personRepository.insert(person));
        JdbcRecentConversationRepository repository = new JdbcRecentConversationRepository(
                jdbcTemplate,
                transactionTemplate,
                3
        );

        List<ConversationTurnSnapshot> turns = List.of(
                turn(ConversationTurnSnapshot.Role.USER, "第一条", 0),
                turn(ConversationTurnSnapshot.Role.PERSON, "第二条", 1),
                turn(ConversationTurnSnapshot.Role.USER, "第三条", 2),
                turn(ConversationTurnSnapshot.Role.PERSON, "第四条", 3)
        );

        int stored = repository.append(person.getId(), turns)
                .toCompletableFuture()
                .join();

        assertThat(stored).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person_conversation_turn WHERE person_id = ?",
                Integer.class,
                person.getId().toString()
        )).isEqualTo(3);

        List<ConversationTurnSnapshot> latestTwo = repository.retrieve(
                new RecentConversationQuery(person.getId(), "ignored", 2)
        ).toCompletableFuture().join();
        assertThat(latestTwo)
                .extracting(ConversationTurnSnapshot::text)
                .containsExactly("第三条", "第四条");

        JdbcRecentConversationRepository restarted = new JdbcRecentConversationRepository(
                new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                3
        );
        List<ConversationTurnSnapshot> restored = restarted.retrieve(
                new RecentConversationQuery(person.getId(), "", 10)
        ).toCompletableFuture().join();

        assertThat(restored)
                .extracting(ConversationTurnSnapshot::text)
                .containsExactly("第二条", "第三条", "第四条");
        assertThat(restored)
                .extracting(ConversationTurnSnapshot::role)
                .containsExactly(
                        ConversationTurnSnapshot.Role.PERSON,
                        ConversationTurnSnapshot.Role.USER,
                        ConversationTurnSnapshot.Role.PERSON
                );
        assertThat(restored)
                .extracting(ConversationTurnSnapshot::occurredAt)
                .containsExactly(
                        NOW.plusSeconds(1),
                        NOW.plusSeconds(2),
                        NOW.plusSeconds(3)
                );
    }

    @Test
    void summarizesOldestBatchLeavesRecentWindowAndUsesOptimisticVersioning() {
        Person person = Person.create(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        assertTrue(personRepository.insert(person));
        JdbcRecentConversationRepository repository = new JdbcRecentConversationRepository(
                jdbcTemplate,
                transactionTemplate,
                100
        );
        repository.append(person.getId(), numberedTurns(1, 20))
                .toCompletableFuture()
                .join();

        ConversationSummaryWorkItem firstWork = repository.findWork(
                person.getId(),
                12,
                8
        ).toCompletableFuture().join().orElseThrow();
        assertThat(firstWork.existingSummary()).isEmpty();
        assertThat(firstWork.turns())
                .extracting(ConversationTurnSnapshot::text)
                .containsExactly(
                        "消息1", "消息2", "消息3", "消息4",
                        "消息5", "消息6", "消息7", "消息8"
                );

        assertThat(repository.save(
                person.getId(),
                firstWork,
                "用户和人物已经讨论了前八条消息。",
                NOW.plusSeconds(100)
        ).toCompletableFuture().join()).isTrue();
        assertThat(repository.save(
                person.getId(),
                firstWork,
                "过期并发写入",
                NOW.plusSeconds(101)
        ).toCompletableFuture().join()).isFalse();

        ConversationSummarySnapshot firstSummary = repository.retrieve(person.getId())
                .toCompletableFuture()
                .join()
                .orElseThrow();
        assertThat(firstSummary.content()).isEqualTo("用户和人物已经讨论了前八条消息。");
        assertThat(firstSummary.summarizedTurnCount()).isEqualTo(8);
        assertThat(firstSummary.version()).isZero();
        assertThat(firstSummary.coveredThroughTurnId())
                .isEqualTo(firstWork.coveredThroughTurnId());

        List<ConversationTurnSnapshot> remaining = repository.retrieve(
                new RecentConversationQuery(person.getId(), "", 19)
        ).toCompletableFuture().join();
        assertThat(remaining).hasSize(12);
        assertThat(remaining.getFirst().text()).isEqualTo("消息9");
        assertThat(remaining.getLast().text()).isEqualTo("消息20");
        assertThat(repository.findWork(person.getId(), 12, 8)
                .toCompletableFuture().join()).isEmpty();

        repository.append(person.getId(), numberedTurns(21, 28))
                .toCompletableFuture()
                .join();
        ConversationSummaryWorkItem secondWork = repository.findWork(
                person.getId(),
                12,
                8
        ).toCompletableFuture().join().orElseThrow();
        assertThat(secondWork.existingSummary()).contains(firstSummary);
        assertThat(secondWork.turns())
                .extracting(ConversationTurnSnapshot::text)
                .containsExactly(
                        "消息9", "消息10", "消息11", "消息12",
                        "消息13", "消息14", "消息15", "消息16"
                );

        assertThat(repository.save(
                person.getId(),
                secondWork,
                "前十六条消息已经被合并成新的滚动摘要。",
                NOW.plusSeconds(200)
        ).toCompletableFuture().join()).isTrue();
        ConversationSummarySnapshot secondSummary = repository.retrieve(person.getId())
                .toCompletableFuture()
                .join()
                .orElseThrow();
        assertThat(secondSummary.version()).isEqualTo(1);
        assertThat(secondSummary.summarizedTurnCount()).isEqualTo(16);
        assertThat(secondSummary.content())
                .isEqualTo("前十六条消息已经被合并成新的滚动摘要。");
    }

    @Test
    void storesDeduplicatesRanksAndCascadesConversationEpisodes() {
        Person person = Person.create(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        assertTrue(personRepository.insert(person));
        JdbcRecentConversationRepository conversationRepository =
                new JdbcRecentConversationRepository(
                        jdbcTemplate,
                        transactionTemplate,
                        100
                );
        conversationRepository.append(person.getId(), numberedTurns(1, 20))
                .toCompletableFuture()
                .join();
        ConversationSummaryWorkItem work = conversationRepository.findWork(
                person.getId(),
                12,
                8
        ).toCompletableFuture().join().orElseThrow();
        JdbcConversationEpisodeRepository episodeRepository =
                new JdbcConversationEpisodeRepository(jdbcTemplate);

        ConversationEpisodeDraft conflict = new ConversationEpisodeDraft(
                "用户调整游戏搭子相处方式",
                "用户因游戏搭子临时去玩 Steam 感到被忽视，讨论后决定简短表达感受。",
                "CONFLICT",
                List.of("用户", "游戏搭子"),
                List.of("失落", "不安全感"),
                "用户决定观察对方后续行动。",
                0.85
        );
        ConversationEpisodeDraft study = new ConversationEpisodeDraft(
                "用户开始准备线性代数考试",
                "用户制定了晚间复习线性代数的计划。",
                "STUDY",
                List.of("用户"),
                List.of("专注"),
                "用户准备当晚开始复习。",
                0.65
        );

        assertThat(episodeRepository.saveAll(
                person.getId(),
                work,
                List.of(conflict, study),
                NOW.plusSeconds(100)
        ).toCompletableFuture().join()).isEqualTo(2);
        assertThat(episodeRepository.saveAll(
                person.getId(),
                work,
                List.of(conflict, study),
                NOW.plusSeconds(101)
        ).toCompletableFuture().join()).isZero();

        List<ConversationEpisodeSnapshot> relevant = episodeRepository.retrieve(
                person.getId(),
                "上次和游戏搭子发生矛盾后我决定怎么办",
                1
        ).toCompletableFuture().join();
        assertThat(relevant).hasSize(1);
        assertThat(relevant.getFirst().episode().title())
                .isEqualTo("用户调整游戏搭子相处方式");
        assertThat(relevant.getFirst().sourceStartTurnId()).isPositive();
        assertThat(relevant.getFirst().sourceEndTurnId())
                .isEqualTo(work.coveredThroughTurnId());

        assertTrue(personRepository.deleteById(person.getId()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person_conversation_episode WHERE person_id = ?",
                Integer.class,
                person.getId().toString()
        )).isZero();
    }

    private static List<ConversationTurnSnapshot> numberedTurns(int start, int end) {
        List<ConversationTurnSnapshot> turns = new ArrayList<>();
        for (int number = start; number <= end; number++) {
            ConversationTurnSnapshot.Role role = number % 2 == 1
                    ? ConversationTurnSnapshot.Role.USER
                    : ConversationTurnSnapshot.Role.PERSON;
            turns.add(turn(role, "消息" + number, number));
        }
        return List.copyOf(turns);
    }

    private static ConversationTurnSnapshot turn(
            ConversationTurnSnapshot.Role role,
            String text,
            long seconds
    ) {
        return new ConversationTurnSnapshot(role, text, NOW.plusSeconds(seconds));
    }
}
