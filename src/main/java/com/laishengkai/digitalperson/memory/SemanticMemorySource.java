package com.laishengkai.digitalperson.memory;

import java.util.concurrent.CompletionStage;

/** Retrieval source backed by semantic or vector memory. */
@FunctionalInterface
public interface SemanticMemorySource {
    CompletionStage<PersonMemoryContext> retrieve(PersonMemoryQuery query);
}
