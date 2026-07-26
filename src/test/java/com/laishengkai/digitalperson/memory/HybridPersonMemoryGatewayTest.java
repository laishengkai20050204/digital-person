package com.laishengkai.digitalperson.memory;

import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class HybridPersonMemoryGatewayTest {
    private static final PersonId PERSON_ID = PersonId.random();
    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void mergesSourcesDeduplicatesEquivalentContentAndAppliesOneGlobalLimit() {
        StructuredMemorySource structured = query -> CompletableFuture.completedFuture(
                PersonMemoryContext.available(List.of(
                        item("structured:1", MemorySection.PREFERENCE, "用户喜欢王者荣耀", 0.92),
                        item("structured:2", MemorySection.RELATIONSHIP, "小林是用户的游戏搭子", 0.75)
                ))
        );
        SemanticMemorySource semantic = query -> CompletableFuture.completedFuture(
                PersonMemoryContext.available(List.of(
                        item("mem0:1", MemorySection.PREFERENCE, " 用户喜欢王者荣耀 ", 0.80),
                        item("mem0:2", MemorySection.EPISODIC, "上周一起打过游戏", 0.70)
                ))
        );
        HybridPersonMemoryGateway gateway = new HybridPersonMemoryGateway(
                structured,
                semantic
        );

        PersonMemoryContext result = gateway.retrieve(query(3))
                .toCompletableFuture()
                .join();

        assertThat(result.availability()).isEqualTo(MemoryAvailability.AVAILABLE);
        assertThat(result.items())
                .extracting(MemoryItem::id)
                .containsExactly("structured:1", "structured:2", "mem0:2");
    }

    @Test
    void availableSourceWinsWhenTheOtherSourceIsUnavailable() {
        StructuredMemorySource structured = query -> CompletableFuture.completedFuture(
                PersonMemoryContext.available(List.of(
                        item("structured:1", MemorySection.IDENTITY, "用户就读于南京信息工程大学", 0.8)
                ))
        );
        SemanticMemorySource semantic = query -> CompletableFuture.failedFuture(
                new IllegalStateException("provider down")
        );

        PersonMemoryContext result = new HybridPersonMemoryGateway(structured, semantic)
                .retrieve(query(5))
                .toCompletableFuture()
                .join();

        assertThat(result.availability()).isEqualTo(MemoryAvailability.AVAILABLE);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void reportsDisabledOnlyWhenBothSourcesAreDisabled() {
        StructuredMemorySource structured = query -> CompletableFuture.completedFuture(
                PersonMemoryContext.disabled()
        );
        SemanticMemorySource semantic = query -> CompletableFuture.completedFuture(
                PersonMemoryContext.disabled()
        );

        PersonMemoryContext result = new HybridPersonMemoryGateway(structured, semantic)
                .retrieve(query(5))
                .toCompletableFuture()
                .join();

        assertThat(result.availability()).isEqualTo(MemoryAvailability.DISABLED);
        assertThat(result.items()).isEmpty();
    }

    private static PersonMemoryQuery query(int maxItems) {
        return new PersonMemoryQuery(
                PERSON_ID,
                "游戏",
                Set.of(),
                maxItems
        );
    }

    private static MemoryItem item(
            String id,
            MemorySection section,
            String content,
            double relevance
    ) {
        return new MemoryItem(id, section, content, relevance, NOW, NOW);
    }
}
