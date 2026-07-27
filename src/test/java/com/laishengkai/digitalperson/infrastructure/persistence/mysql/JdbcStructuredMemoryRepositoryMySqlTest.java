package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laishengkai.digitalperson.infrastructure.memory.StructuredMemoryProperties;
import com.laishengkai.digitalperson.memory.MemoryEntity;
import com.laishengkai.digitalperson.memory.MemoryEntityResolutionQuery;
import com.laishengkai.digitalperson.memory.MemoryEntityType;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.StructuredMemoryAliasDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryEntityDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryFact;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactConflictMode;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactWriteResult;
import com.laishengkai.digitalperson.memory.StructuredMemoryQuery;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.personality.Personality;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcStructuredMemoryRepositoryMySqlTest {
    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("digital_person")
            .withUsername("digital_person")
            .withPassword("digital_person_test");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcPersonRepository personRepository;
    private static JdbcStructuredMemoryRepository repository;

    @BeforeAll
    static void startPersistenceAdapter() {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(MYSQL.getJdbcUrl());
        hikari.setUsername(MYSQL.getUsername());
        hikari.setPassword(MYSQL.getPassword());
        hikari.setMaximumPoolSize(4);
        hikari.setPoolName("structured-memory-test");
        dataSource = new HikariDataSource(hikari);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/mysql")
                .validateMigrationNaming(true)
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        personRepository = new JdbcPersonRepository(
                jdbcTemplate,
                new PersonAggregateJsonMapper(
                        JsonMapper.builder()
                                .addModule(new JavaTimeModule())
                                .build()
                )
        );
        repository = new JdbcStructuredMemoryRepository(
                jdbcTemplate,
                transactionTemplate,
                new StructuredMemoryProperties(true, 0.60, 300)
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
        jdbcTemplate.update("DELETE FROM person_memory_fact");
        jdbcTemplate.update("DELETE FROM memory_entity_alias");
        jdbcTemplate.update("DELETE FROM memory_entity");
        jdbcTemplate.update("DELETE FROM digital_person");
    }

    @Test
    void resolvesAliasesWithOneWrongCharacterAndSearchesByEntityId() {
        Person person = Person.create(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        assertTrue(personRepository.insert(person));

        MemoryEntity friend = repository.upsertEntity(new StructuredMemoryEntityDraft(
                person.getId(),
                MemoryEntityType.PERSON,
                "林晓雨",
                "用户最近认识的游戏搭子",
                NOW
        )).toCompletableFuture().join();
        repository.addAlias(new StructuredMemoryAliasDraft(
                friend.entityId(),
                "小林",
                "DIALOGUE",
                0.9,
                NOW
        )).toCompletableFuture().join();

        assertThat(repository.resolve(new MemoryEntityResolutionQuery(
                person.getId(),
                "林小雨",
                Set.of(MemoryEntityType.PERSON),
                "之前一起打王者的游戏搭子",
                0.60,
                5
        )).toCompletableFuture().join())
                .hasSize(1)
                .first()
                .satisfies(match -> {
                    assertThat(match.entity().entityId()).isEqualTo(friend.entityId());
                    assertThat(match.similarity()).isGreaterThanOrEqualTo(0.60);
                });

        StructuredMemoryFact stored = repository.upsertFact(
                relationshipFact(person, friend)
        ).toCompletableFuture().join();
        StructuredMemoryFact updated = repository.upsertFact(
                relationshipFact(person, friend)
        ).toCompletableFuture().join();

        assertThat(updated.factId()).isEqualTo(stored.factId());
        assertThat(updated.evidenceCount()).isEqualTo(2);
        assertThat(repository.search(new StructuredMemoryQuery(
                person.getId(),
                Set.of(MemorySection.RELATIONSHIP),
                Set.of("SOCIAL"),
                Set.of(friend.entityId()),
                Set.of("RELATION_TO_USER"),
                NOW,
                "那个游戏搭子",
                10
        )).toCompletableFuture().join())
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.fact().statement())
                            .isEqualTo("林晓雨是用户最近认识的游戏搭子");
                    assertThat(match.relevance()).isBetween(0.0, 1.0);
                });
    }

    @Test
    void deduplicatesEvidenceRangesAndSupersedesConservativeFactSlots() {
        Person person = Person.create(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        assertTrue(personRepository.insert(person));
        StructuredMemoryFactDraft nanjing = new StructuredMemoryFactDraft(
                person.getId(),
                MemorySection.USER_PROFILE,
                "PERSONAL",
                "",
                "CURRENT_CITY",
                "",
                "南京",
                "用户当前居住在南京",
                0.92,
                0.80,
                NOW.minusSeconds(86_400),
                null,
                NOW
        );

        StructuredMemoryFactWriteResult first = repository.upsertFactEvidence(
                nanjing,
                1,
                8,
                StructuredMemoryFactConflictMode.KEEP_EXISTING
        ).toCompletableFuture().join();
        StructuredMemoryFactWriteResult retry = repository.upsertFactEvidence(
                nanjing,
                1,
                8,
                StructuredMemoryFactConflictMode.KEEP_EXISTING
        ).toCompletableFuture().join();
        StructuredMemoryFactWriteResult newEvidence = repository.upsertFactEvidence(
                nanjing,
                9,
                16,
                StructuredMemoryFactConflictMode.KEEP_EXISTING
        ).toCompletableFuture().join();

        assertThat(first.evidenceAdded()).isTrue();
        assertThat(retry.evidenceAdded()).isFalse();
        assertThat(retry.fact().evidenceCount()).isEqualTo(1);
        assertThat(newEvidence.fact().evidenceCount()).isEqualTo(2);

        Instant movedAt = NOW.plusSeconds(60);
        StructuredMemoryFactDraft shanghai = new StructuredMemoryFactDraft(
                person.getId(),
                MemorySection.USER_PROFILE,
                "PERSONAL",
                "",
                "CURRENT_CITY",
                "",
                "上海",
                "用户当前居住在上海",
                0.95,
                0.85,
                movedAt,
                null,
                movedAt
        );
        StructuredMemoryFactWriteResult moved = repository.upsertFactEvidence(
                shanghai,
                17,
                24,
                StructuredMemoryFactConflictMode.SUPERSEDE_EXISTING
        ).toCompletableFuture().join();

        assertThat(moved.supersededFactCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT valid_until FROM person_memory_fact WHERE fact_id = ?",
                java.sql.Timestamp.class,
                first.fact().factId()
        ).toInstant()).isEqualTo(movedAt);
        assertThat(repository.search(new StructuredMemoryQuery(
                person.getId(),
                Set.of(MemorySection.USER_PROFILE),
                Set.of("PERSONAL"),
                Set.of(),
                Set.of("CURRENT_CITY"),
                movedAt.plusSeconds(1),
                "用户现在住在哪里",
                10
        )).toCompletableFuture().join())
                .singleElement()
                .satisfies(match -> assertThat(match.fact().textValue()).isEqualTo("上海"));
    }

    @Test
    void excludesExpiredFactsAtTheRequestedValidityTime() {
        Person person = Person.create(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        assertTrue(personRepository.insert(person));
        repository.upsertFact(new StructuredMemoryFactDraft(
                person.getId(),
                MemorySection.WORKING_MEMORY,
                "PROJECT",
                "",
                "CURRENT_BATCH",
                "",
                "第三批",
                "当前正在处理第三批重构",
                1.0,
                0.7,
                NOW.minusSeconds(3_600),
                NOW.minusSeconds(60),
                NOW.minusSeconds(3_600)
        )).toCompletableFuture().join();

        assertThat(repository.search(new StructuredMemoryQuery(
                person.getId(),
                Set.of(MemorySection.WORKING_MEMORY),
                Set.of("PROJECT"),
                Set.of(),
                Set.of(),
                NOW,
                "当前批次",
                10
        )).toCompletableFuture().join()).isEmpty();
    }

    private static StructuredMemoryFactDraft relationshipFact(
            Person person,
            MemoryEntity friend
    ) {
        return new StructuredMemoryFactDraft(
                person.getId(),
                MemorySection.RELATIONSHIP,
                "SOCIAL",
                friend.entityId(),
                "RELATION_TO_USER",
                "",
                "游戏搭子",
                "林晓雨是用户最近认识的游戏搭子",
                0.90,
                0.75,
                NOW.minusSeconds(86_400),
                null,
                NOW
        );
    }
}
