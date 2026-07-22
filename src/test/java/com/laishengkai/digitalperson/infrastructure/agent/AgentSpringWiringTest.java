package com.laishengkai.digitalperson.infrastructure.agent;

import com.laishengkai.digitalperson.agent.AgentExecutor;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelConfiguration;
import com.laishengkai.digitalperson.web.AgentToolLoopTestController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSpringWiringTest {

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(
                            LanguageModelConfiguration.class,
                            AgentConfiguration.class,
                            AgentToolLoopTestController.class
                    );

    @Test
    void shouldRegisterGatewayExecutorAndSmokeControllerWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "digital-person.llm.enabled=true",
                        "digital-person.llm.base-url=https://example.com/v1",
                        "digital-person.llm.api-key=test-key",
                        "digital-person.llm.model=test-model",
                        "digital-person.llm.connection-test.enabled=true",
                        "digital-person.llm.connection-test.token=test-token"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(LanguageModelGateway.class);
                    assertThat(context).hasSingleBean(AgentExecutor.class);
                    assertThat(context).hasSingleBean(
                            AgentToolLoopTestController.class
                    );
                });
    }

    @Test
    void shouldNotRegisterAgentComponentsWhenModelIntegrationIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "digital-person.llm.enabled=false",
                        "digital-person.llm.connection-test.enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LanguageModelGateway.class);
                    assertThat(context).doesNotHaveBean(AgentExecutor.class);
                    assertThat(context).doesNotHaveBean(
                            AgentToolLoopTestController.class
                    );
                });
    }
}
