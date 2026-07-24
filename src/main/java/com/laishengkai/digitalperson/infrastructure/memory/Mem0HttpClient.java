package com.laishengkai.digitalperson.infrastructure.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laishengkai.digitalperson.memory.MemoryMessage;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Minimal asynchronous client for the self-hosted Mem0 OSS REST API. */
final class Mem0HttpClient {
    private final Mem0Properties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    Mem0HttpClient(Mem0Properties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    CompletionStage<Boolean> probe() {
        HttpRequest request = requestBuilder(properties.healthPath())
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> response.statusCode() >= 200
                        && response.statusCode() < 400)
                .exceptionally(ignored -> false);
    }

    CompletionStage<JsonNode> search(PersonMemoryQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put(
                "query",
                query.relevanceQuery().isBlank()
                        ? "relevant long-term person memory"
                        : query.relevanceQuery()
        );
        payload.put("limit", query.maxItems());
        payload.putObject("filters")
                .put("agent_id", query.personId().toString());
        return sendJson("/search", "POST", payload);
    }

    CompletionStage<JsonNode> add(PersonMemoryWriteRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        ObjectNode payload = objectMapper.createObjectNode();
        var messages = payload.putArray("messages");
        for (MemoryMessage message : request.messages()) {
            messages.addObject()
                    .put("role", message.role().name().toLowerCase(Locale.ROOT))
                    .put("content", message.content());
        }
        payload.put("agent_id", request.personId().toString());
        payload.put("infer", request.infer());
        ObjectNode metadata = payload.putObject("metadata");
        metadata.put("source", "digital-person");
        request.metadata().forEach(metadata::put);
        return sendJson("/memories", "POST", payload);
    }

    CompletionStage<Void> delete(String memoryId) {
        String normalized = requireText(memoryId, "memoryId");
        HttpRequest request = requestBuilder("/memories/" + normalized)
                .DELETE()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    requireSuccess(response.statusCode(), response.body());
                    return null;
                });
    }

    private CompletionStage<JsonNode> sendJson(
            String path,
            String method,
            JsonNode payload
    ) {
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(new Mem0ClientException(
                    "failed to serialize Mem0 request",
                    exception
            ));
        }

        HttpRequest request = requestBuilder(path)
                .method(
                        method,
                        HttpRequest.BodyPublishers.ofString(body)
                )
                .header("Content-Type", "application/json")
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(
                        response.statusCode(),
                        response.body()
                ));
    }

    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(properties.endpoint(path))
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json");
        if (!properties.apiKey().isBlank()) {
            builder.header("X-API-Key", properties.apiKey());
        }
        return builder;
    }

    private JsonNode parseResponse(int status, String body) {
        requireSuccess(status, body);
        if (body == null || body.isBlank()) {
            return objectMapper.getNodeFactory().nullNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new CompletionException(new Mem0ClientException(
                    "Mem0 returned invalid JSON",
                    exception
            ));
        }
    }

    private static void requireSuccess(int status, String body) {
        if (status >= 200 && status < 300) {
            return;
        }
        String normalizedBody = body == null ? "" : body.strip();
        if (normalizedBody.length() > 512) {
            normalizedBody = normalizedBody.substring(0, 512);
        }
        throw new CompletionException(new Mem0ClientException(
                "Mem0 request failed with status " + status + ": " + normalizedBody
        ));
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException(fieldName + " contains unsafe characters");
        }
        return normalized;
    }
}
