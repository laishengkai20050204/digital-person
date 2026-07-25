package com.laishengkai.digitalperson.infrastructure.dialogue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PersonDialoguePropertiesBindingTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsCanonicalRecordConstructorWhenCompatibilityConstructorsAlsoExist() {
        contextRunner.withPropertyValues(
                "digital-person.dialogue.max-memory-items=6",
                "digital-person.dialogue.max-conversation-turns=14",
                "digital-person.dialogue.max-output-tokens=1500",
                "digital-person.dialogue.temperature=0.55",
                "digital-person.dialogue.conversation-summary-enabled=true",
                "digital-person.dialogue.conversation-summary-batch-turns=10",
                "digital-person.dialogue.conversation-summary-max-output-tokens=700",
                "digital-person.dialogue.conversation-summary-temperature=0.15",
                "digital-person.dialogue.conversation-episode-enabled=true",
                "digital-person.dialogue.conversation-episode-max-output-tokens=650",
                "digital-person.dialogue.conversation-episode-temperature=0.08"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            PersonDialogueProperties properties = context.getBean(
                    PersonDialogueProperties.class
            );
            assertThat(properties.maxMemoryItems()).isEqualTo(6);
            assertThat(properties.maxConversationTurns()).isEqualTo(14);
            assertThat(properties.maxOutputTokens()).isEqualTo(1500);
            assertThat(properties.temperature()).isEqualTo(0.55);
            assertThat(properties.conversationSummaryEnabled()).isTrue();
            assertThat(properties.conversationSummaryBatchTurns()).isEqualTo(10);
            assertThat(properties.conversationSummaryMaxOutputTokens()).isEqualTo(700);
            assertThat(properties.conversationSummaryTemperature()).isEqualTo(0.15);
            assertThat(properties.conversationEpisodeEnabled()).isTrue();
            assertThat(properties.conversationEpisodeMaxOutputTokens()).isEqualTo(650);
            assertThat(properties.conversationEpisodeTemperature()).isEqualTo(0.08);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PersonDialogueProperties.class)
    static class TestConfiguration {
    }
}
