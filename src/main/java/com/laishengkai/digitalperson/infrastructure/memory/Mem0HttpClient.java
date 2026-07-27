package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryMessage;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Minimal asynchronous client for the self-hosted Mem0 OSS REST API. */
final class Mem0HttpClient {
    private final Mem0Properties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final HttpResponse.BodyHandler<byte[]> responseBodyHandler;

    Mem0HttpClient(Mem0Properties properties, JsonMapper jsonMapper) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper cannot be null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.responseBodyHandler = new LimitedByteArrayBodyHandler(
                properties.maxResponseBytes()
        );
    }

    CompletionStage<Boolean> probe() {
        HttpRequest request = requestBuilder(
                properties.healthPath(),
                properties.requestTimeout()
        )
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> response.statusCode() >= 200
                        && response.statusCode() < 300)
                .exceptionally(ignored -> false);
    }

    CompletionStage<JsonNode> search(PersonMemoryQuery query) {
        return search(query, properties.minimumRelevance());
    }

    CompletionStage<JsonNode> search(PersonMemoryQuery query, double threshold) {
        Objects.requireNonNull(query, "query cannot be null");
        double normalizedThreshold = probability(threshold, "threshold");
        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put(
                "query",
                query.relevanceQuery().isBlank()
                        ? "relevant long-term person memory"
                        : query.relevanceQuery()
        );
        payload.put("top_k", query.maxItems());
        payload.put("threshold", normalizedThreshold);
        payload.putObject("filters")
                .put("agent_id", query.personId().toString());
        return sendJson(
                "/search",
                "POST",
                payload,
                properties.requestTimeout(),
                "search"
        );
    }

    CompletionStage<JsonNode> add(PersonMemoryWriteRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        ObjectNode payload = jsonMapper.createObjectNode();
        var messages = payload.putArray("messages");
        for (MemoryMessage message : request.messages()) {
            messages.addObject()
                    .put("role", message.role().name().toLowerCase(Locale.ROOT))
                    .put("content", message.content());
        }
        payload.put("agent_id", request.personId().toString());
        payload.put("infer", request.infer());
        if (request.infer()) {
            payload.put("prompt", properties.extractionInstructions());
        }
        ObjectNode metadata = payload.putObject("metadata");
        metadata.put("source", "digital-person");
        request.metadata().forEach(metadata::put);
        return sendJson(
                "/memories",
                "POST",
                payload,
                properties.recordingTimeout(),
                "recording"
        );
    }

    CompletionStage<Void> delete(String memoryId) {
        String normalized = requireText(memoryId, "memoryId");
        HttpRequest request = requestBuilder(
                "/memories/" + normalized,
                properties.requestTimeout()
        )
                .DELETE()
                .build();
        return httpClient.sendAsync(request, responseBodyHandler)
                .thenApply(response -> {
                    requireSuccess(response.statusCode());
                    requireJsonContentTypeWhenPresent(response);
                    return null;
                });
    }

    private CompletionStage<JsonNode> sendJson(
            String path,
            String method,
            JsonNode payload,
            Duration timeout,
            String operation
    ) {
        String body;
        try {
            body = jsonMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            return CompletableFuture.failedFuture(new Mem0ClientException(
                    "failed to serialize Mem0 request",
                    exception
            ));
        }

        HttpRequest request = requestBuilder(path, timeout)
                .method(
                        method,
                        HttpRequest.BodyPublishers.ofString(body)
                )
                .header("Content-Type", "application/json")
                .build();
        return httpClient.sendAsync(request, responseBodyHandler)
                .handle((response, failure) -> {
                    if (failure != null) {
                        throw new CompletionException(requestFailure(
                                operation,
                                timeout,
                                failure
                        ));
                    }
                    return parseResponse(response);
                });
    }

    private HttpRequest.Builder requestBuilder(String path, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(properties.endpoint(path))
                .timeout(Objects.requireNonNull(timeout, "timeout cannot be null"))
                .header("Accept", "application/json");
        if (!properties.apiKey().isBlank()) {
            builder.header("X-API-Key", properties.apiKey());
        }
        return builder;
    }

    private static Mem0ClientException requestFailure(
            String operation,
            Duration timeout,
            Throwable failure
    ) {
        Throwable cause = unwrap(failure);
        String safeOperation = requireOperation(operation);
        if (cause instanceof HttpTimeoutException) {
            String message = "Mem0 " + safeOperation + " timed out after " + timeout;
            if ("recording".equals(safeOperation)) {
                message += "; completion is unknown and the POST request was not retried";
            }
            return new Mem0ClientException(message, cause);
        }
        if (cause instanceof Mem0ClientException clientException) {
            return clientException;
        }
        return new Mem0ClientException(
                "Mem0 " + safeOperation + " request failed",
                cause
        );
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure cannot be null");
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private JsonNode parseResponse(HttpResponse<byte[]> response) {
        requireSuccess(response.statusCode());
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            return jsonMapper.getNodeFactory().nullNode();
        }
        requireJsonContentType(response);
        try {
            return jsonMapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (JacksonException exception) {
            throw new CompletionException(new Mem0ClientException(
                    "Mem0 returned invalid JSON",
                    exception
            ));
        }
    }

    private static void requireJsonContentTypeWhenPresent(
            HttpResponse<byte[]> response
    ) {
        byte[] body = response.body();
        if (body != null && body.length > 0) {
            requireJsonContentType(response);
        }
    }

    private static void requireJsonContentType(HttpResponse<?> response) {
        String contentType = response.headers()
                .firstValue("Content-Type")
                .orElse("")
                .toLowerCase(Locale.ROOT);
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator))
                .strip();
        if (!mediaType.equals("application/json") && !mediaType.endsWith("+json")) {
            throw new CompletionException(new Mem0ClientException(
                    "Mem0 returned an unsupported Content-Type"
            ));
        }
    }

    private static void requireSuccess(int status) {
        if (status >= 200 && status < 300) {
            return;
        }
        throw new CompletionException(new Mem0ClientException(
                "Mem0 request failed with status " + status
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

    private static String requireOperation(String value) {
        String normalized = Objects.requireNonNull(value, "operation cannot be null").strip();
        if (!normalized.matches("[a-z]+")) {
            throw new IllegalArgumentException("operation must contain lowercase letters only");
        }
        return normalized;
    }

    private static double probability(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(fieldName + " must be between 0.0 and 1.0");
        }
        return value;
    }
}
