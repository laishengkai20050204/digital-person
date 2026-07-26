package com.laishengkai.digitalperson.memory;

import java.util.concurrent.CompletionStage;

/** Converts natural language into whitelisted structured-memory filters. */
@FunctionalInterface
public interface StructuredMemoryQueryPlanner {
    CompletionStage<StructuredMemoryQueryPlan> plan(PersonMemoryQuery query);
}
