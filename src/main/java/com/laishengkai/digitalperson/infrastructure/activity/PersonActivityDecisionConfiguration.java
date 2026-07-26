package com.laishengkai.digitalperson.infrastructure.activity;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionModel;
import com.laishengkai.digitalperson.application.DefaultPersonActivityDecisionContextAssembler;
import com.laishengkai.digitalperson.application.PersonActivityDecisionContextAssembler;
import com.laishengkai.digitalperson.application.PersonActivityDecisionService;
import com.laishengkai.digitalperson.application.PersonModelContextAssembler;
import com.laishengkai.digitalperson.application.StateEvaluationContextAssembler;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.infrastructure.spring.LateConditionalBeanRegistrar;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.application.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Spring wiring for autonomous event lifecycle decisions. */
@Configuration(proxyBeanMethods = false)
public class PersonActivityDecisionConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.llm",
            name = "enabled",
            havingValue = "true"
    )
    @ConditionalOnMissingBean(PersonActivityDecisionModel.class)
    PersonActivityDecisionModel personActivityDecisionModel(
            LanguageModelGateway languageModelGateway
    ) {
        return new LanguageModelPersonActivityDecisionModel(languageModelGateway);
    }

    @Bean
    @ConditionalOnMissingBean(PersonActivityDecisionContextAssembler.class)
    PersonActivityDecisionContextAssembler personActivityDecisionContextAssembler(
            PersonModelContextAssembler commonAssembler
    ) {
        return new DefaultPersonActivityDecisionContextAssembler(commonAssembler);
    }

    /** Registers the service after model and external port definitions are available. */
    @Bean
    static BeanFactoryPostProcessor personActivityDecisionServiceRegistrar() {
        return new LateConditionalBeanRegistrar(beanFactory ->
                LateConditionalBeanRegistrar.registerIfPossible(
                        beanFactory,
                        "personActivityDecisionService",
                        PersonActivityDecisionService.class,
                        () -> new PersonActivityDecisionService(
                                beanFactory.getBean(PersonRepository.class),
                                beanFactory.getBean(StateUpdater.class),
                                beanFactory.getBean(PersonActivityDecisionModel.class),
                                beanFactory.getBean(
                                        PersonActivityDecisionContextAssembler.class
                                ),
                                beanFactory.getBean(EventStateImpactEvaluator.class),
                                beanFactory.getBean(StateEvaluationContextAssembler.class),
                                beanFactory.getBean(Clock.class)
                        ),
                        PersonRepository.class,
                        StateUpdater.class,
                        PersonActivityDecisionModel.class,
                        PersonActivityDecisionContextAssembler.class,
                        EventStateImpactEvaluator.class,
                        StateEvaluationContextAssembler.class,
                        Clock.class
                )
        );
    }
}
