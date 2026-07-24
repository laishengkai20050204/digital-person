package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.application.DialogueMemoryRecorder;
import com.laishengkai.digitalperson.infrastructure.context.StateEvaluationContextConfiguration;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class Mem0MemoryConfigurationTest {
    private final ApplicationContextRunner contextRunner = contextRunner(
            Mem0MemoryConfiguration.class,
            StateEvaluationContextConfiguration.class
    );

    @Test
    void keepsNoOpRetrievalWhileMem0IsOnlyPrepared() {
        contextRunner
                .withPropertyValues(
                        "digital-person.memory.mem0.enabled=true",
                        "digital-person.memory.mem0.retrieval-enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PersonMemoryStore.class);
                    assertThat(context).hasSingleBean(DialogueMemoryRecorder.class);
                    assertThat(context).hasSingleBean(PersonMemoryGateway.class);
                    assertThat(context.getBean(PersonMemoryGateway.class))
                            .isInstanceOf(NoOpPersonMemoryGateway.class);
                });
    }

    @Test
    void replacesNoOpGatewayOnlyWhenRetrievalIsExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "digital-person.memory.mem0.enabled=true",
                        "digital-person.memory.mem0.retrieval-enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PersonMemoryStore.class);
                    assertThat(context).hasSingleBean(DialogueMemoryRecorder.class);
                    assertThat(context).hasSingleBean(PersonMemoryGateway.class);
                    assertThat(context.getBean(PersonMemoryGateway.class))
                            .isInstanceOf(Mem0PersonMemoryGateway.class);
                });
    }

    @Test
    void selectsMem0WhenFallbackConfigurationIsProcessedFirst() {
        contextRunner(
                StateEvaluationContextConfiguration.class,
                Mem0MemoryConfiguration.class
        )
                .withPropertyValues(
                        "digital-person.memory.mem0.enabled=true",
                        "digital-person.memory.mem0.retrieval-enabled=true"
                )
                .run(context -> {
                    assertThat(context.getBeansOfType(PersonMemoryGateway.class))
                            .containsKeys("personMemoryGateway", "mem0PersonMemoryGateway");
                    assertThat(context.getBean(PersonMemoryGateway.class))
                            .isInstanceOf(Mem0PersonMemoryGateway.class);
                });
    }

    private static ApplicationContextRunner contextRunner(
            Class<?>... configurations
    ) {
        return new ApplicationContextRunner()
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withUserConfiguration(configurations);
    }
}
