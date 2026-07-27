package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryMessage;
import com.laishengkai.digitalperson.memory.MemoryMessageRole;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class Mem0RecordingTimeoutTest {
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void defaultsRecordingToASeparateLongerBudget() {
        Mem0Properties properties = new Mem0Properties(
                true,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.recordingTimeout()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void recordingUsesItsOwnTimeoutInsteadOfTheShortReadTimeout() {
        server.createContext("/memories", exchange -> {
            readBody(exchange);
            pause(Duration.ofMillis(300));
            respondIgnoringDisconnect(exchange, 200, "{\"results\":[]}");
        });
        Mem0PersonMemoryStore store = new Mem0PersonMemoryStore(client(
                Duration.ofMillis(75),
                Duration.ofSeconds(2)
        ));

        assertThat(store.add(writeRequest()).toCompletableFuture().join()).isEmpty();
    }

    @Test
    void recordingTimeoutIsClassifiedAndThePostIsNotBlindlyRetried() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/memories", exchange -> {
            attempts.incrementAndGet();
            readBody(exchange);
            pause(Duration.ofMillis(300));
            respondIgnoringDisconnect(exchange, 200, "{\"results\":[]}");
        });
        Mem0PersonMemoryStore store = new Mem0PersonMemoryStore(client(
                Duration.ofSeconds(1),
                Duration.ofMillis(75)
        ));

        Throwable failure;
        try {
            store.add(writeRequest()).toCompletableFuture().join();
            throw new AssertionError("expected Mem0 recording to time out");
        } catch (CompletionException error) {
            failure = unwrap(error);
        }

        assertThat(failure).isInstanceOf(Mem0ClientException.class);
        assertThat(failure.getMessage())
                .contains("Mem0 recording timed out")
                .contains("completion is unknown")
                .contains("was not retried");
        assertThat(attempts).hasValue(1);
    }

    private Mem0HttpClient client(Duration requestTimeout, Duration recordingTimeout) {
        return new Mem0HttpClient(
                new Mem0Properties(
                        true,
                        false,
                        false,
                        0.30,
                        Mem0Properties.DEFAULT_EXTRACTION_INSTRUCTIONS,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "mem0-test-key",
                        Duration.ofSeconds(1),
                        requestTimeout,
                        recordingTimeout,
                        Mem0Properties.DEFAULT_MAX_RESPONSE_BYTES,
                        "/auth/setup-status"
                ),
                JsonMapper.builder().build()
        );
    }

    private static PersonMemoryWriteRequest writeRequest() {
        return new PersonMemoryWriteRequest(
                PersonId.random(),
                List.of(new MemoryMessage(
                        MemoryMessageRole.USER,
                        "用户形成了一个持续有效的学习计划"
                )),
                Map.of("section", "PLAN"),
                true
        );
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );
    }

    private static void respondIgnoringDisconnect(
            HttpExchange exchange,
            int status,
            String body
    ) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // Expected when the client timeout closes the connection before the test server replies.
        } finally {
            exchange.close();
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("test server delay interrupted", error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
