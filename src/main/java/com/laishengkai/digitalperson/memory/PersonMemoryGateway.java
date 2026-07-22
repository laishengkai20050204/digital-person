package com.laishengkai.digitalperson.memory;

import java.util.concurrent.CompletionStage;

/** Application-owned port for retrieving relevant long-term person memory. */
@FunctionalInterface
public interface PersonMemoryGateway {
    CompletionStage<PersonMemoryContext> retrieve(PersonMemoryQuery query);
}
