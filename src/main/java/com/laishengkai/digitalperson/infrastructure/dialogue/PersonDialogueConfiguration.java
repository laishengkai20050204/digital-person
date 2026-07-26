package com.laishengkai.digitalperson.infrastructure.dialogue;

import com.laishengkai.digitalperson.application.ConversationSummaryService;
import com.laishengkai.digitalperson.application.DialogueMemoryRecorder;
import com.laishengkai.digitalperson.application.PersonCurrentStateProjector;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotationMetadata;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Spring wiring for provider-neutral direct person dialogue capability. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "digital-person.llm",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(PersonDialogueProperties.class)
@Import(PersonDialogueConfiguration.DialogueServiceImportSelector.class)
public class PersonDialogueConfiguration {

    public static final String POST_PROCESSING_EXECUTOR =
            "personDialoguePostProcessingExecutor";

    @Bean(name = POST_PROCESSING_EXECUTOR, destroyMethod = "close")
    @ConditionalOnMissingBean(name = POST_PROCESSING_EXECUTOR)
    ExecutorService personDialoguePostProcessingExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("person-dialogue-post-", 0).factory()
        );
    }

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

    /** Defers repository checks until all regular user configurations are parsed. */
    public static final class DialogueServiceImportSelector
            implements DeferredImportSelector {
        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[]{DialogueServiceConfiguration.class.getName()};
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(
            value = {
                    PersonRepository.class,
                    PersonModelContextAssembler.class,
                    PersonDialogueModel.class,
                    PersonCurrentStateProjector.class,
                    PersonDialogueProperties.class,
                    Clock.class
            },
            name = POST_PROCESSING_EXECUTOR
    )
    static class DialogueServiceConfiguration {

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
                PersonCurrentStateProjector stateProjector,
                @Qualifier(POST_PROCESSING_EXECUTOR) Executor postProcessingExecutor,
                Clock clock,
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
                    stateProjector,
                    postProcessingExecutor,
                    clock,
                    properties.maxMemoryItems(),
                    properties.maxConversationTurns(),
                    properties.conversationSummaryBatchTurns()
            );
        }
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
