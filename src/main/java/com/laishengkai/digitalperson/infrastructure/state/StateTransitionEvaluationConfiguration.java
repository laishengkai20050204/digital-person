package com.laishengkai.digitalperson.infrastructure.state;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for model-backed short-term state evaluation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "digital-person.llm",
        name = "enabled",
        havingValue = "true"
)
public class StateTransitionEvaluationConfiguration {

    @Bean
    @ConditionalOnMissingBean(EventStateImpactEvaluator.class)
    EventStateImpactEvaluator eventStateImpactEvaluator(
            LanguageModelGateway languageModelGateway
    ) {
        return new LanguageModelStateTransitionEvaluator(languageModelGateway);
    }

    @Bean
    @ConditionalOnMissingBean(StateTransitionEvaluationDiagnostic.class)
    StateTransitionEvaluationDiagnostic stateTransitionEvaluationDiagnostic(
            LanguageModelGateway languageModelGateway
    ) {
        return new StateTransitionEvaluationDiagnostic(languageModelGateway);
    }
}
