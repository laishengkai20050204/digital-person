package com.laishengkai.digitalperson.infrastructure.spring;

import com.laishengkai.digitalperson.application.DefaultStateEvaluationContextAssembler;
import com.laishengkai.digitalperson.application.PersonEventCommandService;
import com.laishengkai.digitalperson.application.StateEvaluationContextAssembler;
import com.laishengkai.digitalperson.application.UpdatePersonStateService;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.state.StateTransitionEvaluator;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for provider-neutral person application services. */
@Configuration(proxyBeanMethods = false)
public class PersonApplicationConfiguration {

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

    @Bean
    @ConditionalOnBean({PersonRepository.class, StateTransitionEvaluator.class})
    @ConditionalOnMissingBean(UpdatePersonStateService.class)
    UpdatePersonStateService updatePersonStateService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            StateTransitionEvaluator evaluator,
            StateEvaluationContextAssembler contextAssembler
    ) {
        return new UpdatePersonStateService(
                personRepository,
                stateUpdater,
                evaluator,
                contextAssembler
        );
    }

    @Bean
    @ConditionalOnBean({PersonRepository.class, StateTransitionEvaluator.class})
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
