package com.laishengkai.digitalperson.infrastructure.langchain4j;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageModelConfigurationTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(LanguageModelConfiguration.class);

    @Test
    void shouldNotCreateGatewayWhenIntegrationIsDisabled() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(LanguageModelGateway.class)
        );
    }

    @Test
    void shouldCreateGatewayFromSpringPropertiesWithoutNetworkCall() {
        contextRunner
                .withPropertyValues(
                        "digital-person.llm.enabled=true",
                        "digital-person.llm.base-url=https://openrouter.ai/api/v1",
                        "digital-person.llm.api-key=test-key",
                        "digital-person.llm.model=provider/model",
                        "digital-person.llm.timeout=5s",
                        "digital-person.llm.max-retries=0"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LanguageModelGateway.class);
                    assertThat(context).hasSingleBean(LanguageModelProperties.class);
                });
    }
}
