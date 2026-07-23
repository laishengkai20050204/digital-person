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
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Applies one process-wide bulkhead before requests reach the configured model provider. */
public final class BoundedLanguageModelGateway implements LanguageModelGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            BoundedLanguageModelGateway.class
    );
    private static final Executor VIRTUAL_THREAD_EXECUTOR = command ->
            Thread.startVirtualThread(command);

    private final LanguageModelGateway delegate;
    private final Semaphore permits;
    private final Duration acquireTimeout;
    private final Executor admissionExecutor;

    public BoundedLanguageModelGateway(
            LanguageModelGateway delegate,
            LanguageModelConcurrencyProperties properties
    ) {
        this(
                delegate,
                Objects.requireNonNull(properties, "properties cannot be null").maximum(),
                properties.acquireTimeout(),
                VIRTUAL_THREAD_EXECUTOR
        );
    }

    BoundedLanguageModelGateway(
            LanguageModelGateway delegate,
            int maximumConcurrency,
            Duration acquireTimeout,
            Executor admissionExecutor
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
        this.admissionExecutor = Objects.requireNonNull(
                admissionExecutor,
                "admissionExecutor cannot be null"
        );
    }

    @Override
    public CompletionStage<LanguageModelResponse> invoke(LanguageModelRequest request) {
        LanguageModelRequest safeRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        CompletableFuture<LanguageModelResponse> result = new CompletableFuture<>();
        try {
            admissionExecutor.execute(() -> admit(safeRequest, result));
        } catch (RuntimeException error) {
            result.completeExceptionally(new LanguageModelException(
                    "language model admission executor rejected the request",
                    error
            ));
        }
        return result;
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    private void admit(
            LanguageModelRequest request,
            CompletableFuture<LanguageModelResponse> result
    ) {
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(
                    acquireTimeout.toNanos(),
                    TimeUnit.NANOSECONDS
            );
            if (!acquired) {
                LOGGER.warn(
                        "Language model concurrency limit reached: acquireTimeoutMs={}",
                        acquireTimeout.toMillis()
                );
                result.completeExceptionally(new LanguageModelException(
                        "language model concurrency limit reached"
                ));
                return;
            }

            CompletionStage<LanguageModelResponse> invocation = Objects.requireNonNull(
                    delegate.invoke(request),
                    "delegate stage cannot be null"
            );
            invocation.whenComplete((response, error) -> {
                permits.release();
                if (error == null) {
                    result.complete(response);
                } else {
                    result.completeExceptionally(error);
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (acquired) {
                permits.release();
            }
            result.completeExceptionally(new LanguageModelException(
                    "interrupted while waiting for language model capacity",
                    interrupted
            ));
        } catch (RuntimeException error) {
            if (acquired) {
                permits.release();
            }
            result.completeExceptionally(error instanceof LanguageModelException
                    ? error
                    : new LanguageModelException(
                            "language model invocation could not be started",
                            error
                    ));
        }
    }
}
