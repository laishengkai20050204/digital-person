package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryMutation;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Mem0-backed write adapter used by dialogue orchestration and maintenance flows. */
public final class Mem0PersonMemoryStore implements PersonMemoryStore {
    private final Mem0HttpClient client;

    Mem0PersonMemoryStore(Mem0HttpClient client) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
    }

    @Override
    public CompletionStage<List<MemoryMutation>> add(PersonMemoryWriteRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        return client.add(request).thenApply(Mem0PersonMemoryStore::parseMutations);
    }

    @Override
    public CompletionStage<Void> delete(String memoryId) {
        return client.delete(memoryId);
    }

    private static List<MemoryMutation> parseMutations(JsonNode response) {
        JsonNode results = response != null && response.has("results")
                ? response.get("results")
                : response;
        if (results == null || !results.isArray()) {
            return List.of();
        }
        List<MemoryMutation> mutations = new ArrayList<>();
        for (JsonNode result : results) {
            String id = text(result, "id");
            String event = text(result, "event");
            if (id.isBlank() || event.isBlank()) {
                continue;
            }
            mutations.add(new MemoryMutation(
                    id,
                    text(result, "memory"),
                    event
            ));
        }
        return List.copyOf(mutations);
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value != null && value.isTextual() ? value.asString().strip() : "";
    }
}
