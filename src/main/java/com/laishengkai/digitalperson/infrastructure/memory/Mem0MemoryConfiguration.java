package com.laishengkai.digitalperson.infrastructure.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laishengkai.digitalperson.application.DialogueMemoryRecorder;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for the optional self-hosted Mem0 adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Mem0Properties.class)
public class Mem0MemoryConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.memory.mem0",
            name = "enabled",
            havingValue = "true"
    )
    Mem0HttpClient mem0HttpClient(
            Mem0Properties properties,
            ObjectMapper objectMapper
    ) {
        return new Mem0HttpClient(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.memory.mem0",
            name = "enabled",
            havingValue = "true"
    )
    PersonMemoryStore mem0PersonMemoryStore(Mem0HttpClient client) {
        return new Mem0PersonMemoryStore(client);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.memory.mem0",
            name = "enabled",
            havingValue = "true"
    )
    DialogueMemoryRecorder dialogueMemoryRecorder(PersonMemoryStore memoryStore) {
        return new DialogueMemoryRecorder(memoryStore);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.memory.mem0",
            name = {"enabled", "retrieval-enabled"},
            havingValue = "true"
    )
    PersonMemoryGateway mem0PersonMemoryGateway(Mem0HttpClient client) {
        return new Mem0PersonMemoryGateway(client);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "digital-person.memory.mem0",
            name = "enabled",
            havingValue = "true"
    )
    Mem0StartupProbe mem0StartupProbe(
            Mem0HttpClient client,
            Mem0Properties properties
    ) {
        return new Mem0StartupProbe(client, properties);
    }

    @Bean(name = "mem0")
    @ConditionalOnProperty(
            prefix = "digital-person.memory.mem0",
            name = "enabled",
            havingValue = "true"
    )
    HealthIndicator mem0HealthIndicator(
            Mem0HttpClient client,
            Mem0Properties properties
    ) {
        return new Mem0HealthIndicator(client, properties);
    }
}
