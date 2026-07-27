package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryMessage;
import com.laishengkai.digitalperson.memory.MemoryMessageRole;
import com.laishengkai.digitalperson.memory.MemoryMutation;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class Mem0DeduplicationTest {
    private HttpServer server;
    private final AtomicInteger searchAttempts = new AtomicInteger();
    private final AtomicInteger addAttempts = new AtomicInteger();
    private final AtomicInteger searchStatus = new AtomicInteger(200);
    private final AtomicReference<String> searchBody = new AtomicReference<>();
    private final AtomicReference<String> searchResponse = new AtomicReference<>("{\"results\":[]}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            searchAttempts.incrementAndGet();
            searchBody.set(readBody(exchange));
            respond(exchange, searchStatus.get(), searchResponse.get());
        });
        server.createContext("/memories", exchange -> {
            addAttempts.incrementAndGet();
            readBody(exchange);
            respond(exchange, 200, """
                    {
                      "results": [
                        {
                          "id": "new-memory",
                          "memory": "新增长期记忆",
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
    void suppressesAHighSemanticAndTextualDialogueDuplicate() {
        searchResponse.set("""
                {
                  "results": [
                    {
                      "id": "existing-memory",
                      "memory": "用户决定以后玩马超时都先确认对面关键控制技能交掉再进场收割",
                      "score": 0.64,
                      "metadata": {
                        "source": "dialogue",
                        "section": "CONVERSATION_SUMMARY"
                      },
                      "attributed_to": "user"
                    }
                  ]
                }
                """);

        List<MemoryMutation> mutations = store().add(dialogueRequest(
                "我决定以后每次练马超，都会重点复盘进场前是否确认对方关键控制技能已经交掉。"
        )).toCompletableFuture().join();

        assertThat(mutations).isEmpty();
        assertThat(searchAttempts).hasValue(1);
        assertThat(addAttempts).hasValue(0);
        assertThat(searchBody.get()).contains("\"threshold\":0.62");
        assertThat(searchBody.get()).contains("\"top_k\":5");
    }

    @Test
    void preservesRelatedButDistinctMemories() {
        searchResponse.set("""
                {
                  "results": [
                    {
                      "id": "related-memory",
                      "memory": "用户练习马超后操作取得了明显进步",
                      "score": 0.70,
                      "metadata": {
                        "source": "dialogue",
                        "section": "CONVERSATION_SUMMARY"
                      },
                      "attributed_to": "user"
                    }
                  ]
                }
                """);

        List<MemoryMutation> mutations = store().add(dialogueRequest(
                "我决定以后每次练马超，都会重点复盘进场前是否确认对方关键控制技能已经交掉。"
        )).toCompletableFuture().join();

        assertThat(mutations).containsExactly(new MemoryMutation(
                "new-memory",
                "新增长期记忆",
                "ADD"
        ));
        assertThat(searchAttempts).hasValue(1);
        assertThat(addAttempts).hasValue(1);
    }

    @Test
    void bypassesDeduplicationForExplicitCorrections() {
        List<MemoryMutation> mutations = store().add(dialogueRequest(
                "更正一下，我以后不再把确认控制技能作为固定进场条件。"
        )).toCompletableFuture().join();

        assertThat(mutations).hasSize(1);
        assertThat(searchAttempts).hasValue(0);
        assertThat(addAttempts).hasValue(1);
    }

    @Test
    void failsOpenWhenTheDuplicateLookupIsUnavailable() {
        searchStatus.set(500);
        searchResponse.set("{\"detail\":\"provider unavailable\"}");

        List<MemoryMutation> mutations = store().add(dialogueRequest(
                "我决定以后每次练马超都复盘进场时机。"
        )).toCompletableFuture().join();

        assertThat(mutations).hasSize(1);
        assertThat(searchAttempts).hasValue(1);
        assertThat(addAttempts).hasValue(1);
    }

    @Test
    void productionLikeDuplicateClearsTheTextThresholdButAnAdjacentEventDoesNot() {
        String query = "我决定以后每次练马超，都会重点复盘进场前是否确认对方关键控制技能已经交掉。";

        assertThat(Mem0PersonMemoryStore.textSimilarity(
                query,
                "用户决定以后玩马超时都先确认对面关键控制技能交掉再进场收割"
        )).isGreaterThanOrEqualTo(0.30);
        assertThat(Mem0PersonMemoryStore.textSimilarity(
                query,
                "用户在练习马超时意识到自己进场太急，并开始观察对面控制技能"
        )).isLessThan(0.30);
    }

    private Mem0PersonMemoryStore store() {
        return new Mem0PersonMemoryStore(
                client(),
                true,
                0.62,
                0.30,
                5
        );
    }

    private Mem0HttpClient client() {
        Mem0Properties properties = new Mem0Properties(
                true,
                false,
                true,
                0.30,
                Mem0Properties.DEFAULT_EXTRACTION_INSTRUCTIONS,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "mem0-test-key",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Mem0Properties.DEFAULT_MAX_RESPONSE_BYTES,
                "/auth/setup-status"
        );
        return new Mem0HttpClient(properties, JsonMapper.builder().build());
    }

    private static PersonMemoryWriteRequest dialogueRequest(String message) {
        return new PersonMemoryWriteRequest(
                PersonId.random(),
                List.of(new MemoryMessage(MemoryMessageRole.USER, message)),
                Map.of(
                        "source", "dialogue",
                        "section", "CONVERSATION_SUMMARY"
                ),
                true
        );
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
