package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonEventCommandService;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
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

class PersonEventApiSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    PersonApplicationConfiguration.class,
                    PersonApiConfiguration.class,
                    PersonEventController.class,
                    StateTransitionEvaluationConfiguration.class,
                    RequiredInfrastructure.class
            )
            .withPropertyValues(
                    "digital-person.person-api.enabled=true",
                    "digital-person.person-api.token=test-token",
                    "digital-person.llm.enabled=true"
            );

    @Test
    void registersEventCommandServiceAndControllerRegardlessOfConfigurationOrder() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PersonEventCommandService.class);
            assertThat(context).hasSingleBean(PersonEventController.class);
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
