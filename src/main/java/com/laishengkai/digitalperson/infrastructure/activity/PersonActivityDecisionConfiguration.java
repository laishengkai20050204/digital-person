package com.laishengkai.digitalperson.infrastructure.activity;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionModel;
import com.laishengkai.digitalperson.application.DefaultPersonActivityDecisionContextAssembler;
import com.laishengkai.digitalperson.application.PersonActivityDecisionContextAssembler;
import com.laishengkai.digitalperson.application.PersonActivityDecisionService;
import com.laishengkai.digitalperson.application.PersonModelContextAssembler;
import com.laishengkai.digitalperson.application.StateEvaluationContextAssembler;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import com.laishengkai.digitalperson.state.StateUpdater;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    @ConditionalOnBean({
            PersonRepository.class,
            PersonActivityDecisionModel.class,
            EventStateImpactEvaluator.class
    })
    @ConditionalOnMissingBean(PersonActivityDecisionService.class)
    PersonActivityDecisionService personActivityDecisionService(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            PersonActivityDecisionModel activityDecisionModel,
            PersonActivityDecisionContextAssembler activityContextAssembler,
            EventStateImpactEvaluator effectEvaluator,
            StateEvaluationContextAssembler effectContextAssembler
    ) {
        return new PersonActivityDecisionService(
                personRepository,
                stateUpdater,
                activityDecisionModel,
                activityContextAssembler,
                effectEvaluator,
                effectContextAssembler
        );
    }
}
