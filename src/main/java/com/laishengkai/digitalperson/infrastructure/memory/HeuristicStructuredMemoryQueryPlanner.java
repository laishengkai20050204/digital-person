package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryEntityType;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.memory.StructuredMemoryQueryPlan;
import com.laishengkai.digitalperson.memory.StructuredMemoryQueryPlanner;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Deterministic fallback planner; an LLM planner can replace this port later. */
public final class HeuristicStructuredMemoryQueryPlanner
        implements StructuredMemoryQueryPlanner {

    @Override
    public CompletionStage<StructuredMemoryQueryPlan> plan(PersonMemoryQuery query) {
        PersonMemoryQuery request = Objects.requireNonNull(
                query,
                "query cannot be null"
        );
        String normalized = request.relevanceQuery().toLowerCase(Locale.ROOT);
        Set<MemorySection> inferred = inferredSections(normalized);
        Set<MemorySection> selected = selectSections(request.sections(), inferred);
        Set<MemoryEntityType> entityTypes = relationshipIntent(normalized)
                ? Set.of(MemoryEntityType.PERSON)
                : Set.of();
        String entityMention = normalized.isBlank()
                ? ""
                : request.relevanceQuery();
        return CompletableFuture.completedFuture(new StructuredMemoryQueryPlan(
                selected,
                Set.of(),
                Set.of(),
                entityTypes,
                entityMention
        ));
    }

    private static Set<MemorySection> inferredSections(String query) {
        EnumSet<MemorySection> sections = EnumSet.noneOf(MemorySection.class);
        if (containsAny(query, "日程", "安排", "明天", "今天", "几点", "什么时候", "上课")) {
            sections.addAll(Set.of(
                    MemorySection.SCHEDULE,
                    MemorySection.ROUTINE,
                    MemorySection.PLAN,
                    MemorySection.COMMITMENT
            ));
        }
        if (containsAny(query, "关系", "朋友", "搭子", "室友", "家人", "她", "他")) {
            sections.addAll(Set.of(
                    MemorySection.RELATIONSHIP,
                    MemorySection.EPISODIC,
                    MemorySection.EMOTIONAL_PATTERN
            ));
        }
        if (containsAny(query, "喜欢", "讨厌", "爱好", "兴趣", "玩什么", "吃什么")) {
            sections.addAll(Set.of(
                    MemorySection.PREFERENCE,
                    MemorySection.ROUTINE,
                    MemorySection.USER_PROFILE
            ));
        }
        if (containsAny(query, "答应", "提醒", "约好", "待办", "承诺")) {
            sections.addAll(Set.of(
                    MemorySection.COMMITMENT,
                    MemorySection.SCHEDULE,
                    MemorySection.PLAN
            ));
        }
        if (containsAny(query, "学校", "专业", "年龄", "生日", "住哪", "身份")) {
            sections.addAll(Set.of(
                    MemorySection.IDENTITY,
                    MemorySection.USER_PROFILE
            ));
        }
        if (containsAny(query, "难过", "焦虑", "情绪", "安全感", "失落")) {
            sections.addAll(Set.of(
                    MemorySection.EMOTIONAL_PATTERN,
                    MemorySection.EPISODIC
            ));
        }
        return Set.copyOf(sections);
    }

    private static Set<MemorySection> selectSections(
            Set<MemorySection> requested,
            Set<MemorySection> inferred
    ) {
        if (inferred.isEmpty()) {
            return requested;
        }
        if (requested.isEmpty()) {
            return inferred;
        }
        EnumSet<MemorySection> intersection = EnumSet.copyOf(requested);
        intersection.retainAll(inferred);
        return intersection.isEmpty() ? requested : Set.copyOf(intersection);
    }

    private static boolean relationshipIntent(String query) {
        return containsAny(query, "关系", "朋友", "搭子", "室友", "家人", "她", "他", "谁");
    }

    private static boolean containsAny(String source, String... values) {
        for (String value : values) {
            if (source.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
