package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelConfiguration;
import com.laishengkai.digitalperson.infrastructure.state.StateTransitionEvaluationConfiguration;
import com.laishengkai.digitalperson.infrastructure.state.StateTransitionEvaluationDiagnostic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StateEvaluationDiagnosticControllerSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    LanguageModelConfiguration.class,
                    StateTransitionEvaluationConfiguration.class,
                    StateEvaluationDiagnosticController.class
            );

    @Test
    void shouldRegisterProtectedDiagnosticsWhenTestModeIsEnabled() {
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
                    assertThat(context)
                            .hasSingleBean(StateTransitionEvaluationDiagnostic.class);
                    assertThat(context)
                            .hasSingleBean(StateEvaluationDiagnosticController.class);
                });
    }

    @Test
    void shouldNotRegisterDiagnosticControllerWhenTestModeIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "digital-person.llm.enabled=true",
                        "digital-person.llm.base-url=https://example.com/v1",
                        "digital-person.llm.api-key=test-key",
                        "digital-person.llm.model=test-model",
                        "digital-person.llm.connection-test.enabled=false"
                )
                .run(context -> assertThat(context)
                        .doesNotHaveBean(StateEvaluationDiagnosticController.class));
    }
}
