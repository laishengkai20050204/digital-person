package com.laishengkai.digitalperson.infrastructure.context;

import com.laishengkai.digitalperson.application.DefaultStateEvaluationContextAssembler;
import com.laishengkai.digitalperson.application.StateEvaluationContextAssembler;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.infrastructure.conversation.NoOpRecentConversationGateway;
import com.laishengkai.digitalperson.infrastructure.memory.NoOpPersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default context retrieval wiring; real providers can replace every bean. */
@Configuration(proxyBeanMethods = false)
public class StateEvaluationContextConfiguration {

    @Bean
    @ConditionalOnMissingBean(PersonMemoryGateway.class)
    PersonMemoryGateway personMemoryGateway() {
        return new NoOpPersonMemoryGateway();
    }

    @Bean
    @ConditionalOnMissingBean(RecentConversationGateway.class)
    RecentConversationGateway recentConversationGateway() {
        return new NoOpRecentConversationGateway();
    }

    @Bean
    @ConditionalOnMissingBean(StateEvaluationContextAssembler.class)
    StateEvaluationContextAssembler stateEvaluationContextAssembler(
            PersonMemoryGateway memoryGateway,
            RecentConversationGateway conversationGateway
    ) {
        return new DefaultStateEvaluationContextAssembler(
                memoryGateway,
                conversationGateway
        );
    }
}
