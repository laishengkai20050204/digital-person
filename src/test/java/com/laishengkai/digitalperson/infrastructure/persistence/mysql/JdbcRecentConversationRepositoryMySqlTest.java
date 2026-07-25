package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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

    private static ConversationTurnSnapshot turn(
            ConversationTurnSnapshot.Role role,
            String text,
            long seconds
    ) {
        return new ConversationTurnSnapshot(role, text, NOW.plusSeconds(seconds));
    }
}
