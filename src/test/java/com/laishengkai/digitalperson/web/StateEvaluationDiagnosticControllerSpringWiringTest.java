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
                    StateEvaluationDiagnosticController.class,
                    StateEvaluationContrastController.class
            )
            .withPropertyValues(
                    "digital-person.llm.enabled=true",
                    "digital-person.llm.base-url=https://example.com/v1",
                    "digital-person.llm.api-key=test-key",
                    "digital-person.llm.model=test-model"
            );

    @Test
    void registersRawDiagnosticsOnlyWhenDedicatedSwitchIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "digital-person.diagnostics.enabled=true",
                        "digital-person.diagnostics.token=test-token",
                        "digital-person.llm.connection-test.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(StateTransitionEvaluationDiagnostic.class);
                    assertThat(context)
                            .hasSingleBean(StateEvaluationDiagnosticController.class);
                    assertThat(context)
                            .hasSingleBean(StateEvaluationContrastController.class);
                });
    }

    @Test
    void connectionTestSwitchDoesNotExposeRawDiagnostics() {
        contextRunner
                .withPropertyValues(
                        "digital-person.diagnostics.enabled=false",
                        "digital-person.llm.connection-test.enabled=true",
                        "digital-person.llm.connection-test.token=test-token"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(StateTransitionEvaluationDiagnostic.class);
                    assertThat(context)
                            .doesNotHaveBean(StateEvaluationDiagnosticController.class);
                    assertThat(context)
                            .doesNotHaveBean(StateEvaluationContrastController.class);
                });
    }

    @Test
    void enabledDiagnosticsRequireTheirOwnToken() {
        contextRunner
                .withPropertyValues("digital-person.diagnostics.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
