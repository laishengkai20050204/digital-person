package com.laishengkai.digitalperson.memory;

import java.util.concurrent.CompletionStage;

/** Retrieval source backed by typed facts and canonical entities. */
@FunctionalInterface
public interface StructuredMemorySource {
    CompletionStage<PersonMemoryContext> retrieve(PersonMemoryQuery query);
}
