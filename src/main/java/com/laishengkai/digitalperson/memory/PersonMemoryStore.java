package com.laishengkai.digitalperson.memory;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Application-owned write port for a long-term-memory provider. */
public interface PersonMemoryStore {
    CompletionStage<List<MemoryMutation>> add(PersonMemoryWriteRequest request);

    CompletionStage<Void> delete(String memoryId);
}
