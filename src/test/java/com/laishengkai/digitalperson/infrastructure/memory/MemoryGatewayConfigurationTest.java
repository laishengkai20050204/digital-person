package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.HybridPersonMemoryGateway;
import com.laishengkai.digitalperson.memory.MemoryAvailability;
import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.memory.SemanticMemorySource;
import com.laishengkai.digitalperson.memory.StructuredMemorySource;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryGatewayConfigurationTest {

    @Test
    void combinesOptionalSourcesBehindOnePrimaryGateway() {
        Instant now = Instant.parse("2026-07-26T12:00:00Z");
        StructuredMemorySource structured = query -> CompletableFuture.completedFuture(
                PersonMemoryContext.available(List.of(new MemoryItem(
                        "structured:1",
                        MemorySection.IDENTITY,
                        "用户就读于南京信息工程大学",
                        0.9,
                        now,
                        now
                )))
        );
        SemanticMemorySource semantic = query -> CompletableFuture.completedFuture(
                PersonMemoryContext.available(List.of(new MemoryItem(
                        "mem0:1",
                        MemorySection.EPISODIC,
                        "用户之前讨论过考试复习",
                        0.8,
                        now,
                        now
                )))
        );

        new ApplicationContextRunner()
                .withBean(StructuredMemorySource.class, () -> structured)
                .withBean(SemanticMemorySource.class, () -> semantic)
                .withUserConfiguration(MemoryGatewayConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PersonMemoryGateway.class);
                    PersonMemoryGateway gateway = context.getBean(PersonMemoryGateway.class);
                    assertThat(gateway).isInstanceOf(HybridPersonMemoryGateway.class);
                    PersonMemoryContext result = gateway.retrieve(new PersonMemoryQuery(
                            PersonId.random(),
                            "考试",
                            Set.of(),
                            5
                    )).toCompletableFuture().join();
                    assertThat(result.availability())
                            .isEqualTo(MemoryAvailability.AVAILABLE);
                    assertThat(result.items()).hasSize(2);
                });
    }

    @Test
    void keepsAnExplicitApplicationGatewayOverride() {
        PersonMemoryGateway custom = query -> CompletableFuture.completedFuture(
                PersonMemoryContext.disabled()
        );

        new ApplicationContextRunner()
                .withBean(PersonMemoryGateway.class, () -> custom)
                .withUserConfiguration(MemoryGatewayConfiguration.class)
                .run(context -> assertThat(context.getBean(PersonMemoryGateway.class))
                        .isSameAs(custom));
    }
}
