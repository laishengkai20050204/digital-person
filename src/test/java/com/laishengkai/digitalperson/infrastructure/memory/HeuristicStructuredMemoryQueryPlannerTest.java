package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryEntityType;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicStructuredMemoryQueryPlannerTest {
    private final HeuristicStructuredMemoryQueryPlanner planner =
            new HeuristicStructuredMemoryQueryPlanner();

    @Test
    void narrowsRelationshipQuestionsAndRequestsPersonResolution() {
        var plan = planner.plan(new PersonMemoryQuery(
                PersonId.random(),
                "我跟小林最近关系怎么样？",
                EnumSet.allOf(MemorySection.class),
                10
        )).toCompletableFuture().join();

        assertThat(plan.sections()).containsExactlyInAnyOrder(
                MemorySection.RELATIONSHIP,
                MemorySection.EPISODIC,
                MemorySection.EMOTIONAL_PATTERN
        );
        assertThat(plan.entityTypes()).containsExactly(MemoryEntityType.PERSON);
        assertThat(plan.entityMention()).isEqualTo("我跟小林最近关系怎么样？");
    }

    @Test
    void narrowsScheduleQuestionsWithoutGeneratingSqlOrFieldNames() {
        var plan = planner.plan(new PersonMemoryQuery(
                PersonId.random(),
                "我明天有什么安排",
                EnumSet.allOf(MemorySection.class),
                10
        )).toCompletableFuture().join();

        assertThat(plan.sections()).containsExactlyInAnyOrder(
                MemorySection.SCHEDULE,
                MemorySection.ROUTINE,
                MemorySection.PLAN,
                MemorySection.COMMITMENT
        );
        assertThat(plan.domains()).isEmpty();
        assertThat(plan.predicates()).isEmpty();
    }
}
