package com.laishengkai.digitalperson.infrastructure.dialogue;

import com.laishengkai.digitalperson.application.DialogueMemoryRecorder;
import com.laishengkai.digitalperson.application.PersonDialogueService;
import com.laishengkai.digitalperson.application.PersonModelContextAssembler;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.PersonDialogueModel;
import com.laishengkai.digitalperson.person.PersonRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/** Spring wiring for the token-protected direct person dialogue API. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "digital-person.llm",
        name = "enabled",
        havingValue = "true"
)
@ConditionalOnProperty(
        prefix = "digital-person.person-api",
        name = "enabled",
        havingValue = "true"
)
@ConditionalOnBean({
        LanguageModelGateway.class,
        PersonRepository.class,
        PersonModelContextAssembler.class
})
@EnableConfigurationProperties(PersonDialogueProperties.class)
public class PersonDialogueConfiguration {

    @Bean
    @ConditionalOnMissingBean(PersonDialogueModel.class)
    PersonDialogueModel personDialogueModel(
            LanguageModelGateway languageModelGateway,
            JsonMapper jsonMapper,
            PersonDialogueProperties properties
    ) {
        return new LanguageModelPersonDialogueModel(
                languageModelGateway,
                jsonMapper,
                properties
        );
    }

    @Bean
    @ConditionalOnMissingBean(PersonDialogueService.class)
    PersonDialogueService personDialogueService(
            PersonRepository personRepository,
            PersonModelContextAssembler contextAssembler,
            PersonDialogueModel dialogueModel,
            ObjectProvider<DialogueMemoryRecorder> memoryRecorderProvider,
            PersonDialogueProperties properties
    ) {
        return new PersonDialogueService(
                personRepository,
                contextAssembler,
                dialogueModel,
                memoryRecorderProvider.getIfAvailable(),
                Clock.systemUTC(),
                properties.maxMemoryItems(),
                properties.maxConversationTurns()
        );
    }
}
