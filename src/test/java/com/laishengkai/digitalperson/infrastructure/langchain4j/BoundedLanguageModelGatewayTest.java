package com.laishengkai.digitalperson.infrastructure.langchain4j;

import com.laishengkai.digitalperson.async.CancellableCompletableFuture;
import com.laishengkai.digitalperson.dialogue.LanguageModelException;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BoundedLanguageModelGatewayTest {

    @Test
    void rejectsExcessConcurrentCallsAndReleasesCapacityAfterCompletion() throws Exception {
        LanguageModelRequest request = mock(LanguageModelRequest.class);
        LanguageModelResponse firstResponse = mock(LanguageModelResponse.class);
        LanguageModelResponse laterResponse = mock(LanguageModelResponse.class);
        CompletableFuture<LanguageModelResponse> firstInvocation = new CompletableFuture<>();
        CountDownLatch firstAdmitted = new CountDownLatch(1);
        AtomicInteger delegateCalls = new AtomicInteger();

        LanguageModelGateway delegate = ignored -> {
            int call = delegateCalls.incrementAndGet();
            if (call == 1) {
                firstAdmitted.countDown();
                return firstInvocation;
            }
            return CompletableFuture.completedFuture(laterResponse);
        };
        BoundedLanguageModelGateway gateway = new BoundedLanguageModelGateway(
                delegate,
                new LanguageModelConcurrencyProperties(1, Duration.ofMillis(25))
        );

        CompletableFuture<LanguageModelResponse> first = gateway.invoke(request)
                .toCompletableFuture();
        assertTrue(firstAdmitted.await(2, TimeUnit.SECONDS));

        CompletionException rejected = assertThrows(
                CompletionException.class,
                () -> gateway.invoke(request).toCompletableFuture().join()
        );
        assertInstanceOf(LanguageModelException.class, rejected.getCause());
        assertEquals(1, delegateCalls.get());

        firstInvocation.complete(firstResponse);
        assertSame(firstResponse, first.join());
        assertEquals(1, gateway.availablePermits());

        assertSame(laterResponse, gateway.invoke(request).toCompletableFuture().join());
        assertEquals(2, delegateCalls.get());
        assertEquals(1, gateway.availablePermits());
    }

    @Test
    void releasesThePermitWhenTheProviderFails() {
        LanguageModelRequest request = mock(LanguageModelRequest.class);
        LanguageModelResponse response = mock(LanguageModelResponse.class);
        AtomicInteger calls = new AtomicInteger();
        LanguageModelGateway delegate = ignored -> calls.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new LanguageModelException("provider down"))
                : CompletableFuture.completedFuture(response);
        BoundedLanguageModelGateway gateway = new BoundedLanguageModelGateway(
                delegate,
                new LanguageModelConcurrencyProperties(1, Duration.ZERO)
        );

        assertThrows(
                CompletionException.class,
                () -> gateway.invoke(request).toCompletableFuture().join()
        );
        assertEquals(1, gateway.availablePermits());
        assertSame(response, gateway.invoke(request).toCompletableFuture().join());
        assertEquals(1, gateway.availablePermits());
    }
    @Test
    void cancellationDoesNotReleaseCapacityBeforePhysicalTermination() {
        LanguageModelRequest request = mock(LanguageModelRequest.class);
        CompletableFuture<Void> physicalTermination = new CompletableFuture<>();
        CancellableCompletableFuture<LanguageModelResponse> providerInvocation =
                new CancellableCompletableFuture<>(
                        physicalTermination,
                        ignored -> {
                        }
                );
        LanguageModelGateway delegate = ignored -> providerInvocation;
        BoundedLanguageModelGateway gateway = new BoundedLanguageModelGateway(
                delegate,
                new LanguageModelConcurrencyProperties(1, Duration.ZERO)
        );

        CompletableFuture<LanguageModelResponse> first = gateway.invoke(request)
                .toCompletableFuture();
        assertTrue(first.cancel(true));
        assertTrue(providerInvocation.isCancelled());
        assertEquals(0, gateway.availablePermits());

        CompletionException rejected = assertThrows(
                CompletionException.class,
                () -> gateway.invoke(request).toCompletableFuture().join()
        );
        assertInstanceOf(LanguageModelException.class, rejected.getCause());

        physicalTermination.complete(null);
        assertEquals(1, gateway.availablePermits());
    }

}
