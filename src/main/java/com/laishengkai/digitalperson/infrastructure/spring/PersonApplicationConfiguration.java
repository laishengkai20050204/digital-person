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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotationMetadata;

import java.time.Clock;

/** Spring wiring for provider-neutral person application services. */
@Configuration(proxyBeanMethods = false)
@Import({
        StateEvaluationContextConfiguration.class,
        PersonApplicationConfiguration.ApplicationServicesImportSelector.class
})
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

    /** Defers capability conditions until all regular user configurations are parsed. */
    public static final class ApplicationServicesImportSelector
            implements DeferredImportSelector {
        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[]{ApplicationServicesConfiguration.class.getName()};
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationServicesConfiguration {

        /** Directory capability exists only when both persistence ports exist. */
        @Bean
        @ConditionalOnBean({PersonRepository.class, PersonCreationRepository.class})
        @ConditionalOnMissingBean(PersonDirectoryService.class)
        PersonDirectoryService personDirectoryService(
                PersonRepository personRepository,
                PersonCreationRepository creationRepository,
                Clock clock
        ) {
            return new PersonDirectoryService(personRepository, creationRepository, clock);
        }

        /** Core person-event capability is available to any adapter with its ports. */
        @Bean
        @ConditionalOnBean({
                PersonRepository.class,
                EventStateImpactEvaluator.class,
                StateEvaluationContextAssembler.class
        })
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
}
