package com.laishengkai.digitalperson.infrastructure.langchain4j;

import com.laishengkai.digitalperson.dialogue.LanguageModelException;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Applies one process-wide bulkhead before requests reach the configured model provider. */
public final class BoundedLanguageModelGateway implements LanguageModelGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            BoundedLanguageModelGateway.class
    );

    private final LanguageModelGateway delegate;
    private final Semaphore permits;
    private final Duration acquireTimeout;

    public BoundedLanguageModelGateway(
            LanguageModelGateway delegate,
            LanguageModelConcurrencyProperties properties
    ) {
        this(
                delegate,
                Objects.requireNonNull(properties, "properties cannot be null").maximum(),
                properties.acquireTimeout()
        );
    }

    BoundedLanguageModelGateway(
            LanguageModelGateway delegate,
            int maximumConcurrency,
            Duration acquireTimeout
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        if (maximumConcurrency < 1) {
            throw new IllegalArgumentException("maximumConcurrency must be positive");
        }
        this.acquireTimeout = Objects.requireNonNull(
                acquireTimeout,
                "acquireTimeout cannot be null"
        );
        if (acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("acquireTimeout cannot be negative");
        }
        this.permits = new Semaphore(maximumConcurrency, true);
    }

    @Override
    public CompletionStage<LanguageModelResponse> invoke(LanguageModelRequest request) {
        LanguageModelRequest safeRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        final boolean acquired;
        try {
            acquired = permits.tryAcquire(
                    acquireTimeout.toNanos(),
                    TimeUnit.NANOSECONDS
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(new LanguageModelException(
                    "interrupted while waiting for language model capacity",
                    interrupted
            ));
        }
        if (!acquired) {
            LOGGER.warn(
                    "Language model concurrency limit reached: acquireTimeoutMs={}",
                    acquireTimeout.toMillis()
            );
            return CompletableFuture.failedFuture(new LanguageModelException(
                    "language model concurrency limit reached"
            ));
        }

        final CompletionStage<LanguageModelResponse> invocation;
        try {
            invocation = Objects.requireNonNull(
                    delegate.invoke(safeRequest),
                    "delegate stage cannot be null"
            );
        } catch (RuntimeException error) {
            permits.release();
            return CompletableFuture.failedFuture(error instanceof LanguageModelException
                    ? error
                    : new LanguageModelException(
                            "language model invocation could not be started",
                            error
                    ));
        }

        CompletableFuture<LanguageModelResponse> result = new CompletableFuture<>();
        invocation.whenComplete((response, error) -> {
            permits.release();
            if (error == null) {
                result.complete(response);
            } else {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    int availablePermits() {
        return permits.availablePermits();
    }
}
