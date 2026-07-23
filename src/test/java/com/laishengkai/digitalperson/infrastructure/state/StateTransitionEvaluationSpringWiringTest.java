package com.laishengkai.digitalperson.infrastructure.state;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelConfiguration;
import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StateTransitionEvaluationSpringWiringTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            LanguageModelConfiguration.class,
                            StateTransitionEvaluationConfiguration.class
                    );

    @Test
    void shouldRegisterGatewayAndStateEvaluatorsWhenModelIntegrationIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "digital-person.llm.enabled=true",
                        "digital-person.llm.base-url=https://example.com/v1",
                        "digital-person.llm.api-key=test-key",
                        "digital-person.llm.model=test-model"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(LanguageModelGateway.class);
                    assertThat(context).hasSingleBean(EventStateImpactEvaluator.class);
                    assertThat(context.getBean(EventStateImpactEvaluator.class))
                            .isInstanceOf(LanguageModelStateTransitionEvaluator.class);
                });
    }

    @Test
    void shouldNotRegisterStateEvaluatorsWhenModelIntegrationIsDisabled() {
        contextRunner
                .withPropertyValues("digital-person.llm.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LanguageModelGateway.class);
                    assertThat(context).doesNotHaveBean(EventStateImpactEvaluator.class);
                });
    }
}
