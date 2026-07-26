package com.laishengkai.digitalperson.infrastructure.dialogue;

import com.laishengkai.digitalperson.application.PersonDialogueService;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.infrastructure.spring.PersonApplicationConfiguration;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.web.PersonApiConfiguration;
import com.laishengkai.digitalperson.web.PersonDialogueController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class PersonDialogueSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    PersonApplicationConfiguration.class,
                    PersonDialogueConfiguration.class,
                    PersonApiConfiguration.class,
                    PersonDialogueController.class,
                    RequiredInfrastructure.class
            )
            .withPropertyValues(
                    "digital-person.llm.enabled=true",
                    "digital-person.person-api.enabled=false"
            );

    @Test
    void keepsDialogueApplicationServiceWhenHttpApiIsDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PersonDialogueService.class);
            assertThat(context).doesNotHaveBean(PersonDialogueController.class);
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

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
