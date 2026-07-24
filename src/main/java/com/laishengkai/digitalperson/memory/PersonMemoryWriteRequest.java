package com.laishengkai.digitalperson.memory;

import com.laishengkai.digitalperson.person.PersonId;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral request for extracting or directly storing long-term memories. */
public record PersonMemoryWriteRequest(
        PersonId personId,
        List<MemoryMessage> messages,
        Map<String, String> metadata,
        boolean infer
) {
    public PersonMemoryWriteRequest {
        personId = Objects.requireNonNull(personId, "personId cannot be null");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages cannot be null"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be empty");
        }
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("messages cannot contain null");
        }
        metadata = Map.copyOf(Objects.requireNonNullElse(metadata, Map.of()));
        if (metadata.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null)) {
            throw new NullPointerException("metadata cannot contain null keys or values");
        }
    }
}
