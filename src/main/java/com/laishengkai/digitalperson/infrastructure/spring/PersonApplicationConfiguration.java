package com.laishengkai.digitalperson.infrastructure.spring;

import com.laishengkai.digitalperson.application.PersonCurrentStateProjector;
import com.laishengkai.digitalperson.application.PersonDirectoryService;
import com.laishengkai.digitalperson.application.PersonEventCommandService;
import com.laishengkai.digitalperson.application.StateEvaluationContextAssembler;
import com.laishengkai.digitalperson.infrastructure.context.StateEvaluationContextConfiguration;
import com.laishengkai.digitalperson.person.PersonCreationRepository;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;

/** Spring wiring for provider-neutral person application services. */
@Configuration(proxyBeanMethods = false)
@Import(StateEvaluationContextConfiguration.class)
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
    @ConditionalOnMissingBean
    PersonCurrentStateProjector personCurrentStateProjector(StateUpdater stateUpdater) {
        return new PersonCurrentStateProjector(stateUpdater);
    }

    /** Registers port-dependent capabilities after every regular bean definition is known. */
    @Bean
    static BeanFactoryPostProcessor personApplicationServiceRegistrar() {
        return new LateConditionalBeanRegistrar(beanFactory -> {
            LateConditionalBeanRegistrar.registerIfPossible(
                    beanFactory,
                    "personDirectoryService",
                    PersonDirectoryService.class,
                    () -> new PersonDirectoryService(
                            beanFactory.getBean(PersonRepository.class),
                            beanFactory.getBean(PersonCreationRepository.class),
                            beanFactory.getBean(Clock.class)
                    ),
                    PersonRepository.class,
                    PersonCreationRepository.class,
                    Clock.class
            );
            LateConditionalBeanRegistrar.registerIfPossible(
                    beanFactory,
                    "personEventCommandService",
                    PersonEventCommandService.class,
                    () -> new PersonEventCommandService(
                            beanFactory.getBean(PersonRepository.class),
                            beanFactory.getBean(StateUpdater.class),
                            beanFactory.getBean(EventStateImpactEvaluator.class),
                            beanFactory.getBean(StateEvaluationContextAssembler.class)
                    ),
                    PersonRepository.class,
                    StateUpdater.class,
                    EventStateImpactEvaluator.class,
                    StateEvaluationContextAssembler.class
            );
        });
    }
}
