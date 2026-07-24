package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Mem0-backed retrieval adapter with fail-open behavior. */
public final class Mem0PersonMemoryGateway implements PersonMemoryGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            Mem0PersonMemoryGateway.class
    );

    private final Mem0HttpClient client;
    private final double minimumRelevance;

    Mem0PersonMemoryGateway(Mem0HttpClient client, double minimumRelevance) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        if (!Double.isFinite(minimumRelevance)
                || minimumRelevance < 0.0
                || minimumRelevance > 1.0) {
            throw new IllegalArgumentException(
                    "minimumRelevance must be between 0.0 and 1.0"
            );
        }
        this.minimumRelevance = minimumRelevance;
    }

    @Override
    public CompletionStage<PersonMemoryContext> retrieve(PersonMemoryQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        return client.search(query).handle((response, failure) -> {
            if (failure != null) {
                LOGGER.warn(
                        "Mem0 retrieval failed; continuing without long-term memory: personId={}",
                        query.personId(),
                        failure
                );
                return PersonMemoryContext.unavailable();
            }
            try {
                return PersonMemoryContext.available(parse(response, query));
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Mem0 response could not be mapped; continuing without long-term memory: personId={}",
                        query.personId(),
                        exception
                );
                return PersonMemoryContext.unavailable();
            }
        });
    }

    private List<MemoryItem> parse(
            JsonNode response,
            PersonMemoryQuery query
    ) {
        JsonNode results = response != null && response.has("results")
                ? response.get("results")
                : response;
        if (results == null || !results.isArray()) {
            return List.of();
        }

        List<MemoryItem> items = new ArrayList<>();
        for (JsonNode result : results) {
            String id = firstText(result, "id", "memory_id");
            String content = firstText(result, "memory", "text", "data");
            if (id.isBlank() || content.isBlank()) {
                continue;
            }
            MemorySection section = section(result.path("metadata").path("section"));
            if (!query.sections().isEmpty() && !query.sections().contains(section)) {
                continue;
            }
            double relevance = score(result);
            if (relevance < minimumRelevance) {
                continue;
            }
            items.add(new MemoryItem(
                    id,
                    section,
                    content,
                    relevance,
                    timestamp(result, "created_at"),
                    timestamp(result, "updated_at")
            ));
            if (items.size() >= query.maxItems()) {
                break;
            }
        }
        return List.copyOf(items);
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual() && !value.asString().isBlank()) {
                return value.asString().strip();
            }
        }
        return "";
    }

    private static MemorySection section(JsonNode node) {
        if (node != null && node.isTextual()) {
            try {
                return MemorySection.valueOf(node.asString().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Unknown provider metadata falls back to a stable generic section.
            }
        }
        return MemorySection.EPISODIC;
    }

    private static double score(JsonNode node) {
        JsonNode score = node.get("score");
        double value = score != null && score.isNumber() ? score.asDouble() : 1.0;
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static Instant timestamp(JsonNode node, String fieldName) {
        String value = firstText(node, fieldName);
        if (value.isBlank()) {
            value = firstText(node.path("metadata"), fieldName);
        }
        if (value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
