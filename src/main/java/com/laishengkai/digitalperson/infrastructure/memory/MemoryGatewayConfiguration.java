package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.HybridPersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.SemanticMemorySource;
import com.laishengkai.digitalperson.memory.StructuredMemoryQueryPlanner;
import com.laishengkai.digitalperson.memory.StructuredMemorySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.CompletableFuture;

/** Always exposes one primary gateway while optional sources remain independent. */
@Configuration(proxyBeanMethods = false)
public class MemoryGatewayConfiguration {

    @Bean
    @ConditionalOnMissingBean(StructuredMemoryQueryPlanner.class)
    StructuredMemoryQueryPlanner structuredMemoryQueryPlanner() {
        return new HeuristicStructuredMemoryQueryPlanner();
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(PersonMemoryGateway.class)
    PersonMemoryGateway personMemoryGateway(
            ObjectProvider<StructuredMemorySource> structuredProvider,
            ObjectProvider<SemanticMemorySource> semanticProvider
    ) {
        StructuredMemorySource structured = structuredProvider.getIfAvailable(
                () -> query -> CompletableFuture.completedFuture(
                        PersonMemoryContext.disabled()
                )
        );
        SemanticMemorySource semantic = semanticProvider.getIfAvailable(
                () -> query -> CompletableFuture.completedFuture(
                        PersonMemoryContext.disabled()
                )
        );
        return new HybridPersonMemoryGateway(structured, semantic);
    }
}
