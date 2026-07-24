package com.laishengkai.digitalperson.infrastructure.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laishengkai.digitalperson.infrastructure.context.StateEvaluationContextConfiguration;
import com.laishengkai.digitalperson.infrastructure.memory.NoOpPersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class Mem0MemoryConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(
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
                    assertThat(context).hasSingleBean(PersonMemoryGateway.class);
                    assertThat(context.getBean(PersonMemoryGateway.class))
                            .isInstanceOf(Mem0PersonMemoryGateway.class);
                });
    }
}
