package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryAvailability;
import com.laishengkai.digitalperson.memory.MemoryMessage;
import com.laishengkai.digitalperson.memory.MemoryMessageRole;
import com.laishengkai.digitalperson.memory.MemoryMutation;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest;
import com.laishengkai.digitalperson.person.PersonId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class Mem0AdaptersTest {
    private HttpServer server;
    private final AtomicReference<String> searchBody = new AtomicReference<>();
    private final AtomicReference<String> addBody = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> deleteMethod = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/setup-status", exchange -> respond(
                exchange,
                200,
                "{\"needsSetup\":false}"
        ));
        server.createContext("/search", exchange -> {
            searchBody.set(readBody(exchange));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            respond(exchange, 200, """
                    {
                      "results": [
                        {
                          "id": "memory-1",
                          "memory": "用户重视及时回复和明确承诺",
                          "score": 0.82,
                          "created_at": "2026-07-20T01:00:00Z",
                          "updated_at": "2026-07-21T01:00:00Z",
                          "metadata": {"section": "RELATIONSHIP"}
                        }
                      ]
                    }
                    """);
        });
        server.createContext("/memories/memory-1", exchange -> {
            deleteMethod.set(exchange.getRequestMethod());
            respond(exchange, 200, "{\"message\":\"deleted\"}");
        });
        server.createContext("/memories", exchange -> {
            addBody.set(readBody(exchange));
            respond(exchange, 200, """
                    {
                      "results": [
                        {
                          "id": "memory-1",
                          "memory": "用户喜欢科幻电影",
                          "event": "ADD"
                        }
                      ]
                    }
                    """);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mapsSearchResultsAndUsesAgentScopedFilters() {
        PersonId personId = PersonId.random();
        Mem0HttpClient client = client(true);
        Mem0PersonMemoryGateway gateway = new Mem0PersonMemoryGateway(client);

        PersonMemoryContext context = gateway.retrieve(new PersonMemoryQuery(
                personId,
                "回复消息",
                Set.of(MemorySection.RELATIONSHIP),
                5
        )).toCompletableFuture().join();

        assertThat(context.availability()).isEqualTo(MemoryAvailability.AVAILABLE);
        assertThat(context.items()).hasSize(1);
        assertThat(context.items().getFirst().id()).isEqualTo("memory-1");
        assertThat(context.items().getFirst().section())
                .isEqualTo(MemorySection.RELATIONSHIP);
        assertThat(context.items().getFirst().relevance()).isEqualTo(0.82);
        assertThat(context.items().getFirst().createdAt())
                .isEqualTo(Instant.parse("2026-07-20T01:00:00Z"));
        assertThat(searchBody.get()).contains("\"agent_id\":\"" + personId + "\"");
        assertThat(searchBody.get()).contains("\"top_k\":5");
        assertThat(apiKey.get()).isEqualTo("mem0-test-key");
    }

    @Test
    void mapsWriteAndDeleteOperations() {
        PersonId personId = PersonId.random();
        Mem0PersonMemoryStore store = new Mem0PersonMemoryStore(client(false));

        List<MemoryMutation> mutations = store.add(new PersonMemoryWriteRequest(
                personId,
                List.of(
                        new MemoryMessage(MemoryMessageRole.USER, "我喜欢科幻电影"),
                        new MemoryMessage(MemoryMessageRole.ASSISTANT, "我记住了")
                ),
                Map.of("section", "PREFERENCE"),
                true
        )).toCompletableFuture().join();

        store.delete("memory-1").toCompletableFuture().join();

        assertThat(mutations).containsExactly(new MemoryMutation(
                "memory-1",
                "用户喜欢科幻电影",
                "ADD"
        ));
        assertThat(addBody.get()).contains("\"agent_id\":\"" + personId + "\"");
        assertThat(addBody.get()).contains("\"role\":\"user\"");
        assertThat(addBody.get()).contains("\"section\":\"PREFERENCE\"");
        assertThat(deleteMethod.get()).isEqualTo("DELETE");
    }

    @Test
    void returnsUnavailableInsteadOfFailingTheCaller() {
        server.removeContext("/search");
        server.createContext("/search", exchange -> respond(
                exchange,
                500,
                "{\"detail\":\"provider failed\"}"
        ));
        Mem0PersonMemoryGateway gateway = new Mem0PersonMemoryGateway(client(false));

        PersonMemoryContext context = gateway.retrieve(new PersonMemoryQuery(
                PersonId.random(),
                "test",
                Set.of(),
                5
        )).toCompletableFuture().join();

        assertThat(context.availability()).isEqualTo(MemoryAvailability.UNAVAILABLE);
        assertThat(context.items()).isEmpty();
    }

    @Test
    void providerErrorBodiesAreNotExposedThroughExceptions() {
        server.removeContext("/memories");
        server.createContext("/memories", exchange -> respond(
                exchange,
                500,
                "{\"detail\":\"private-memory-content-DO-NOT-LOG\"}"
        ));
        Mem0PersonMemoryStore store = new Mem0PersonMemoryStore(client(false));

        Throwable failure;
        try {
            store.add(new PersonMemoryWriteRequest(
                    PersonId.random(),
                    List.of(new MemoryMessage(
                            MemoryMessageRole.USER,
                            "private user message"
                    )),
                    Map.of(),
                    true
            )).toCompletableFuture().join();
            throw new AssertionError("expected Mem0 write to fail");
        } catch (CompletionException error) {
            failure = unwrap(error);
        }

        assertThat(failure.getMessage()).contains("status 500");
        assertThat(failure.getMessage()).doesNotContain("private-memory-content");
    }

    @Test
    void probesTheOpenSetupStatusEndpoint() {
        assertThat(client(false).probe().toCompletableFuture().join()).isTrue();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private Mem0HttpClient client(boolean retrievalEnabled) {
        URI baseUrl = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
        Mem0Properties properties = new Mem0Properties(
                true,
                false,
                retrievalEnabled,
                baseUrl,
                "mem0-test-key",
                Duration.ofSeconds(1),
                Duration.ofSeconds(3),
                "/auth/setup-status"
        );
        return new Mem0HttpClient(properties, JsonMapper.builder().build());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
