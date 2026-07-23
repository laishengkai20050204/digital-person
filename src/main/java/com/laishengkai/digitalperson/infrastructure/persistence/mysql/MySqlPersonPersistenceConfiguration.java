package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.laishengkai.digitalperson.infrastructure.scheduling.ActivitySchedulerProperties;
import com.laishengkai.digitalperson.infrastructure.scheduling.PersonActivityScheduleRepository;
import com.laishengkai.digitalperson.person.PersonCreationRepository;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;

/** Creates the MySQL repository only when explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        MySqlPersonPersistenceProperties.class,
        ActivitySchedulerProperties.class
})
@ConditionalOnProperty(
        prefix = "digital-person.persistence.mysql",
        name = "enabled",
        havingValue = "true"
)
public class MySqlPersonPersistenceConfiguration {

    @Bean(name = "personDataSource", destroyMethod = "close")
    HikariDataSource personDataSource(MySqlPersonPersistenceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.requiredJdbcUrl());
        config.setUsername(properties.requiredUsername());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(properties.maximumPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(properties.connectionTimeout().toMillis());
        config.setPoolName("digital-person-mysql");
        config.setAutoCommit(true);
        return new HikariDataSource(config);
    }

    @Bean(name = "personFlyway", initMethod = "migrate")
    Flyway personFlyway(
            @Qualifier("personDataSource") DataSource dataSource
    ) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/mysql")
                .validateMigrationNaming(true)
                .load();
    }

    @Bean(name = "personJdbcTemplate")
    JdbcTemplate personJdbcTemplate(
            @Qualifier("personDataSource") DataSource dataSource,
            @Qualifier("personFlyway") Flyway ignoredFlyway
    ) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "personTransactionManager")
    DataSourceTransactionManager personTransactionManager(
            @Qualifier("personDataSource") DataSource dataSource
    ) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = "personTransactionTemplate")
    TransactionTemplate personTransactionTemplate(
            @Qualifier("personTransactionManager")
            DataSourceTransactionManager transactionManager
    ) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    PersonAggregateJsonMapper personAggregateJsonMapper() {
        return new PersonAggregateJsonMapper(
                JsonMapper.builder()
                        .addModule(new JavaTimeModule())
                        .build()
        );
    }

    @Bean
    JdbcPersonRepository jdbcPersonRepository(
            @Qualifier("personJdbcTemplate") JdbcTemplate jdbcTemplate,
            PersonAggregateJsonMapper aggregateMapper
    ) {
        return new JdbcPersonRepository(jdbcTemplate, aggregateMapper);
    }

    @Bean
    PersonActivityScheduleRepository personActivityScheduleRepository(
            @Qualifier("personJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        return new JdbcPersonActivityScheduleRepository(jdbcTemplate);
    }

    /**
     * Primary creation capability: aggregate insertion and first schedule provisioning
     * commit or roll back together on the same MySQL datasource.
     */
    @Bean
    @Primary
    PersonCreationRepository scheduledPersonCreationRepository(
            JdbcPersonRepository personRepository,
            PersonActivityScheduleRepository scheduleRepository,
            ActivitySchedulerProperties schedulerProperties,
            Clock clock,
            @Qualifier("personTransactionTemplate") TransactionTemplate transactionTemplate
    ) {
        return new ScheduledPersonCreationRepository(
                personRepository,
                scheduleRepository,
                schedulerProperties,
                clock,
                transactionTemplate
        );
    }
}
