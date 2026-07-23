package com.laishengkai.digitalperson.infrastructure.state;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.infrastructure.diagnostics.DiagnosticsProperties;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for model-backed short-term state evaluation. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiagnosticsProperties.class)
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
        return new LanguageModelStateTransitionEvaluator(
                new StateEffectProtocolGateway(languageModelGateway)
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.diagnostics",
            name = "enabled",
            havingValue = "true"
    )
    @ConditionalOnMissingBean(StateTransitionEvaluationDiagnostic.class)
    StateTransitionEvaluationDiagnostic stateTransitionEvaluationDiagnostic(
            LanguageModelGateway languageModelGateway,
            DiagnosticsProperties properties
    ) {
        properties.requiredToken();
        return new StateTransitionEvaluationDiagnostic(languageModelGateway);
    }
}
