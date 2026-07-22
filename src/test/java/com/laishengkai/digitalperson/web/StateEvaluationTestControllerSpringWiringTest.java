package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelConfiguration;
import com.laishengkai.digitalperson.infrastructure.state.StateTransitionEvaluationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StateEvaluationTestControllerSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    LanguageModelConfiguration.class,
                    StateTransitionEvaluationConfiguration.class,
                    StateEvaluationTestController.class
            );

    @Test
    void shouldRegisterControllerOnlyWhenLlmAndConnectionTestAreEnabled() {
        contextRunner
                .withPropertyValues(
                        "digital-person.llm.enabled=true",
                        "digital-person.llm.base-url=https://example.com/v1",
                        "digital-person.llm.api-key=test-key",
                        "digital-person.llm.model=test-model",
                        "digital-person.llm.connection-test.enabled=true",
                        "digital-person.llm.connection-test.token=test-token"
                )
                .run(context -> assertThat(context)
                        .hasSingleBean(StateEvaluationTestController.class));
    }

    @Test
    void shouldNotRegisterControllerWhenConnectionTestIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "digital-person.llm.enabled=true",
                        "digital-person.llm.base-url=https://example.com/v1",
                        "digital-person.llm.api-key=test-key",
                        "digital-person.llm.model=test-model",
                        "digital-person.llm.connection-test.enabled=false"
                )
                .run(context -> assertThat(context)
                        .doesNotHaveBean(StateEvaluationTestController.class));
    }
}
