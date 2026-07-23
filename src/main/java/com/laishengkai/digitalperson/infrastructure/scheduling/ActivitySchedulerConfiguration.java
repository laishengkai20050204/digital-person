package com.laishengkai.digitalperson.infrastructure.scheduling;

import com.laishengkai.digitalperson.application.PersonActivityDecisionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** Enables database-backed autonomous activity scheduling when explicitly requested. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ActivitySchedulerProperties.class)
@ConditionalOnProperty(
        prefix = "digital-person.activity-scheduler",
        name = "enabled",
        havingValue = "true"
)
public class ActivitySchedulerConfiguration {

    @Bean
    @ConditionalOnMissingBean(PersistentPersonActivityScheduler.class)
    PersistentPersonActivityScheduler persistentPersonActivityScheduler(
            PersonActivityScheduleRepository scheduleRepository,
            PersonActivityDecisionService decisionService,
            ActivitySchedulerProperties properties,
            Clock clock
    ) {
        return new PersistentPersonActivityScheduler(
                scheduleRepository,
                decisionService,
                properties,
                clock
        );
    }
}