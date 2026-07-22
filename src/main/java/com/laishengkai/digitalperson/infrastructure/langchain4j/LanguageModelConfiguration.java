package com.laishengkai.digitalperson.infrastructure.langchain4j;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the LangChain4j language-model adapter.
 *
 * <p>The gateway is absent by default. Enabling it requires a valid API key and
 * model identifier, so deployments without model secrets continue to start
 * normally.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LanguageModelProperties.class)
public class LanguageModelConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.llm",
            name = "enabled",
            havingValue = "true"
    )
    LanguageModelGateway languageModelGateway(
            LanguageModelProperties properties
    ) {
        return new LangChain4jLanguageModel(properties.toModelConfig());
    }
}
