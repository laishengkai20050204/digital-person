package com.laishengkai.digitalperson.infrastructure.context;

import com.laishengkai.digitalperson.application.StateEvaluationContextAssembler;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.infrastructure.conversation.NoOpRecentConversationGateway;
import com.laishengkai.digitalperson.infrastructure.memory.NoOpPersonMemoryGateway;
import com.laishengkai.digitalperson.memory.MemoryAvailability;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StateEvaluationContextConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(StateEvaluationContextConfiguration.class);

    @Test
    void registersExplicitNoOpProvidersAndAssemblerByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PersonMemoryGateway.class);
            assertThat(context).hasSingleBean(RecentConversationGateway.class);
            assertThat(context).hasSingleBean(StateEvaluationContextAssembler.class);
            assertThat(context.getBean(PersonMemoryGateway.class))
                    .isInstanceOf(NoOpPersonMemoryGateway.class);
            assertThat(context.getBean(RecentConversationGateway.class))
                    .isInstanceOf(NoOpRecentConversationGateway.class);

            PersonMemoryGateway memory = context.getBean(PersonMemoryGateway.class);
            assertThat(memory.retrieve(new PersonMemoryQuery(
                            PersonId.random(),
                            "test",
                            Set.of(MemorySection.RELATIONSHIP),
                            5
                    )).toCompletableFuture().join().availability())
                    .isEqualTo(MemoryAvailability.DISABLED);
        });
    }
}
