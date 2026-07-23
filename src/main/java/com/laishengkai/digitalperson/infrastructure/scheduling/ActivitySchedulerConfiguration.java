package com.laishengkai.digitalperson.infrastructure.scheduling;

import com.laishengkai.digitalperson.application.PersonActivityDecisionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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

    @Bean(name = "activityTaskScheduler", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "activityTaskScheduler")
    ThreadPoolTaskScheduler activityTaskScheduler(ActivitySchedulerProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.maxInFlight() + 1);
        scheduler.setThreadNamePrefix("activity-scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean(PersonActivityLeaseHeartbeat.class)
    PersonActivityLeaseHeartbeat personActivityLeaseHeartbeat(
            PersonActivityScheduleRepository scheduleRepository,
            ActivitySchedulerProperties properties,
            Clock clock,
            @Qualifier("activityTaskScheduler") TaskScheduler taskScheduler
    ) {
        return new PersonActivityLeaseHeartbeat(
                scheduleRepository,
                properties,
                clock,
                taskScheduler
        );
    }

    @Bean
    @ConditionalOnMissingBean(PersistentPersonActivityScheduler.class)
    PersistentPersonActivityScheduler persistentPersonActivityScheduler(
            PersonActivityScheduleRepository scheduleRepository,
            PersonActivityDecisionService decisionService,
            PersonActivityLeaseHeartbeat leaseHeartbeat,
            ActivitySchedulerProperties properties,
            Clock clock
    ) {
        return new PersistentPersonActivityScheduler(
                scheduleRepository,
                decisionService,
                leaseHeartbeat,
                properties,
                clock
        );
    }
}
