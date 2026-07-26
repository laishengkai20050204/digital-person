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
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotationMetadata;

import java.time.Clock;

/** Spring wiring for autonomous event lifecycle decisions. */
@Configuration(proxyBeanMethods = false)
@Import(PersonActivityDecisionConfiguration.ActivityServiceImportSelector.class)
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

    /** Defers port checks until model and external bean definitions are registered. */
    public static final class ActivityServiceImportSelector
            implements DeferredImportSelector {
        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[]{ActivityServiceConfiguration.class.getName()};
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ActivityServiceConfiguration {

        /** Creates the autonomous activity boundary whenever all required ports exist. */
        @Bean
        @ConditionalOnBean({
                PersonActivityDecisionModel.class,
                PersonRepository.class,
                PersonModelContextAssembler.class,
                EventStateImpactEvaluator.class,
                StateEvaluationContextAssembler.class
        })
        @ConditionalOnMissingBean(PersonActivityDecisionService.class)
        PersonActivityDecisionService personActivityDecisionService(
                PersonRepository personRepository,
                StateUpdater stateUpdater,
                PersonActivityDecisionModel activityDecisionModel,
                PersonActivityDecisionContextAssembler activityContextAssembler,
                EventStateImpactEvaluator effectEvaluator,
                StateEvaluationContextAssembler effectContextAssembler,
                Clock clock
        ) {
            return new PersonActivityDecisionService(
                    personRepository,
                    stateUpdater,
                    activityDecisionModel,
                    activityContextAssembler,
                    effectEvaluator,
                    effectContextAssembler,
                    clock
            );
        }
    }
}
