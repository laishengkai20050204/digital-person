package com.laishengkai.digitalperson.infrastructure.dialogue;

import com.laishengkai.digitalperson.application.ConversationSummaryService;
import com.laishengkai.digitalperson.application.DialogueMemoryRecorder;
import com.laishengkai.digitalperson.application.PersonDialogueService;
import com.laishengkai.digitalperson.application.PersonModelContextAssembler;
import com.laishengkai.digitalperson.conversation.ConversationEpisodeStore;
import com.laishengkai.digitalperson.conversation.ConversationSummaryStore;
import com.laishengkai.digitalperson.conversation.RecentConversationStore;
import com.laishengkai.digitalperson.dialogue.ConversationEpisodeModel;
import com.laishengkai.digitalperson.dialogue.ConversationSummaryModel;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.PersonDialogueModel;
import com.laishengkai.digitalperson.person.PersonRepository;
import org.springframework.beans.factory.ObjectProvider;
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
    @ConditionalOnProperty(
            prefix = "digital-person.dialogue",
            name = "conversation-summary-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    @ConditionalOnMissingBean(ConversationSummaryModel.class)
    ConversationSummaryModel conversationSummaryModel(
            LanguageModelGateway languageModelGateway,
            JsonMapper jsonMapper,
            PersonDialogueProperties properties
    ) {
        return new LanguageModelConversationSummaryModel(
                languageModelGateway,
                jsonMapper,
                properties
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.dialogue",
            name = "conversation-episode-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    @ConditionalOnMissingBean(ConversationEpisodeModel.class)
    ConversationEpisodeModel conversationEpisodeModel(
            LanguageModelGateway languageModelGateway,
            JsonMapper jsonMapper,
            PersonDialogueProperties properties
    ) {
        return new LanguageModelConversationEpisodeModel(
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
            ObjectProvider<RecentConversationStore> conversationStoreProvider,
            ObjectProvider<ConversationSummaryStore> summaryStoreProvider,
            ObjectProvider<ConversationSummaryModel> summaryModelProvider,
            ObjectProvider<ConversationEpisodeStore> episodeStoreProvider,
            ObjectProvider<ConversationEpisodeModel> episodeModelProvider,
            ObjectProvider<DialogueMemoryRecorder> memoryRecorderProvider,
            PersonDialogueProperties properties
    ) {
        ConversationSummaryService summaryService = summaryService(
                summaryStoreProvider,
                summaryModelProvider,
                episodeStoreProvider,
                episodeModelProvider,
                properties
        );
        return new PersonDialogueService(
                personRepository,
                contextAssembler,
                dialogueModel,
                conversationStoreProvider.getIfAvailable(),
                summaryService,
                memoryRecorderProvider.getIfAvailable(),
                Clock.systemUTC(),
                properties.maxMemoryItems(),
                properties.maxConversationTurns(),
                properties.conversationSummaryBatchTurns()
        );
    }

    private static ConversationSummaryService summaryService(
            ObjectProvider<ConversationSummaryStore> storeProvider,
            ObjectProvider<ConversationSummaryModel> modelProvider,
            ObjectProvider<ConversationEpisodeStore> episodeStoreProvider,
            ObjectProvider<ConversationEpisodeModel> episodeModelProvider,
            PersonDialogueProperties properties
    ) {
        if (!properties.conversationSummaryEnabled()) {
            return null;
        }
        ConversationSummaryStore store = storeProvider.getIfAvailable();
        ConversationSummaryModel model = modelProvider.getIfAvailable();
        if (store == null || model == null) {
            return null;
        }

        ConversationEpisodeStore episodeStore = null;
        ConversationEpisodeModel episodeModel = null;
        if (properties.conversationEpisodeEnabled()) {
            episodeStore = episodeStoreProvider.getIfAvailable();
            episodeModel = episodeModelProvider.getIfAvailable();
            if (episodeStore == null || episodeModel == null) {
                episodeStore = null;
                episodeModel = null;
            }
        }
        return new ConversationSummaryService(
                store,
                model,
                episodeStore,
                episodeModel,
                properties.maxConversationTurns(),
                properties.conversationSummaryBatchTurns()
        );
    }
}
