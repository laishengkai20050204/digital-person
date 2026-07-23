package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionModel;
import com.laishengkai.digitalperson.application.PersonActivityDecisionContextAssembler;
import com.laishengkai.digitalperson.application.PersonActivityDecisionService;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.infrastructure.activity.PersonActivityDecisionConfiguration;
import com.laishengkai.digitalperson.infrastructure.context.StateEvaluationContextConfiguration;
import com.laishengkai.digitalperson.infrastructure.spring.PersonApplicationConfiguration;
import com.laishengkai.digitalperson.infrastructure.state.StateTransitionEvaluationConfiguration;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class PersonActivityDecisionApiSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    PersonApplicationConfiguration.class,
                    PersonApiConfiguration.class,
                    PersonActivityDecisionController.class,
                    PersonActivityDecisionConfiguration.class,
                    StateTransitionEvaluationConfiguration.class,
                    StateEvaluationContextConfiguration.class,
                    RequiredInfrastructure.class
            )
            .withPropertyValues(
                    "digital-person.person-api.enabled=true",
                    "digital-person.person-api.token=test-token",
                    "digital-person.llm.enabled=true"
            );

    @Test
    void registersActivityDecisionServiceAndControllerRegardlessOfConfigurationOrder() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PersonActivityDecisionModel.class);
            assertThat(context).hasSingleBean(PersonActivityDecisionContextAssembler.class);
            assertThat(context).hasSingleBean(PersonActivityDecisionService.class);
            assertThat(context).hasSingleBean(PersonActivityDecisionController.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredInfrastructure {

        @Bean
        PersonRepository personRepository() {
            return new PersonRepository() {
                @Override
                public Optional<VersionedPerson> findById(PersonId personId) {
                    return Optional.empty();
                }

                @Override
                public boolean save(Person person, long expectedVersion) {
                    return false;
                }
            };
        }

        @Bean
        LanguageModelGateway languageModelGateway() {
            return request -> CompletableFuture.failedFuture(
                    new UnsupportedOperationException("not invoked by wiring test")
            );
        }
    }
}
