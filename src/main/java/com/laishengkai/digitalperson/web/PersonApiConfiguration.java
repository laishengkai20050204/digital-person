package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonEventCommandService;
import com.laishengkai.digitalperson.application.StateEvaluationContextAssembler;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.state.StateTransitionEvaluator;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Registers external configuration and required runtime services for the person API. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersonApiProperties.class)
public class PersonApiConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock personApiClock() {
        return Clock.systemUTC();
    }

    /**
     * Creates the event command boundary whenever the protected person API is enabled.
     * Required dependencies are constructor-resolved instead of discovered through an
     * order-sensitive {@code ConditionalOnBean} check.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.person-api",
            name = "enabled",
            havingValue = "true"
    )
    @ConditionalOnMissingBean(PersonEventCommandService.class)
    PersonEventCommandService personEventCommandService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            StateTransitionEvaluator evaluator,
            StateEvaluationContextAssembler contextAssembler
    ) {
        return new PersonEventCommandService(
                personRepository,
                stateUpdater,
                evaluator,
                contextAssembler
        );
    }
}
