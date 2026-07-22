package com.laishengkai.digitalperson.infrastructure.agent;

import com.laishengkai.digitalperson.agent.AgentExecutor;
import com.laishengkai.digitalperson.agent.DefaultAgentExecutor;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for the application-owned agent execution loop. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(LanguageModelGateway.class)
public class AgentConfiguration {

    @Bean
    AgentExecutor agentExecutor(LanguageModelGateway languageModelGateway) {
        return new DefaultAgentExecutor(languageModelGateway);
    }
}
