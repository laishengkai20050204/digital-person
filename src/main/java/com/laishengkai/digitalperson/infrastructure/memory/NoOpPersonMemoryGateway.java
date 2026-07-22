package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Explicit disabled-memory implementation used until a provider such as Mem0 is configured. */
public final class NoOpPersonMemoryGateway implements PersonMemoryGateway {
    @Override
    public CompletionStage<PersonMemoryContext> retrieve(PersonMemoryQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        return CompletableFuture.completedFuture(PersonMemoryContext.disabled());
    }
}
