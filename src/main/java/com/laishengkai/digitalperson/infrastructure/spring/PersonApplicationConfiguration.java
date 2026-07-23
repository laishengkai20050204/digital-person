package com.laishengkai.digitalperson.infrastructure.spring;

import com.laishengkai.digitalperson.application.DefaultStateEvaluationContextAssembler;
import com.laishengkai.digitalperson.application.PersonDirectoryService;
import com.laishengkai.digitalperson.application.PersonEventCommandService;
import com.laishengkai.digitalperson.application.StateEvaluationContextAssembler;
import com.laishengkai.digitalperson.application.UpdatePersonStateService;
import com.laishengkai.digitalperson.person.PersonCreationRepository;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Spring wiring for provider-neutral person application services. */
@Configuration(proxyBeanMethods = false)
public class PersonApplicationConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    StateUpdater stateUpdater() {
        return new StateUpdater();
    }

    @Bean
    @ConditionalOnMissingBean(StateEvaluationContextAssembler.class)
    StateEvaluationContextAssembler stateEvaluationContextAssembler() {
        return DefaultStateEvaluationContextAssembler.withoutExternalSources();
    }

    /**
     * The protected person API owns the directory boundary explicitly. Required
     * persistence dependencies are constructor-resolved so an enabled API fails
     * startup clearly instead of silently omitting routes because of configuration order.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.person-api",
            name = "enabled",
            havingValue = "true"
    )
    @ConditionalOnMissingBean(PersonDirectoryService.class)
    PersonDirectoryService personDirectoryService(
            PersonRepository personRepository,
            PersonCreationRepository creationRepository
    ) {
        return new PersonDirectoryService(personRepository, creationRepository);
    }

    @Bean
    @ConditionalOnBean({PersonRepository.class, EventStateImpactEvaluator.class})
    @ConditionalOnMissingBean(UpdatePersonStateService.class)
    UpdatePersonStateService updatePersonStateService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            EventStateImpactEvaluator evaluator,
            StateEvaluationContextAssembler contextAssembler
    ) {
        return new UpdatePersonStateService(
                personRepository,
                stateUpdater,
                evaluator,
                contextAssembler
        );
    }

    /**
     * Core person-event application services are owned here, not by the web adapter.
     * Property-based registration avoids the previous order-sensitive ConditionalOnBean
     * path while keeping the API disabled by default.
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
            EventStateImpactEvaluator evaluator,
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
