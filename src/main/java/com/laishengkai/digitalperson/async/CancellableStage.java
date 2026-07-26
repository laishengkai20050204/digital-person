package com.laishengkai.digitalperson.async;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Optional capability exposed by asynchronous operations whose logical result
 * can complete before the underlying work has physically terminated.
 */
public interface CancellableStage {

    /** Requests best-effort cancellation of the underlying work. */
    boolean requestCancellation(boolean mayInterruptIfRunning);

    /**
     * Completes only after the underlying work has stopped and held resources
     * may safely be released.
     */
    CompletionStage<Void> termination();

    static boolean cancel(
            CompletionStage<?> stage,
            boolean mayInterruptIfRunning
    ) {
        CompletionStage<?> safeStage = Objects.requireNonNull(
                stage,
                "stage cannot be null"
        );
        try {
            if (safeStage instanceof CancellableStage cancellable) {
                return cancellable.requestCancellation(mayInterruptIfRunning);
            }
            return safeStage.toCompletableFuture().cancel(mayInterruptIfRunning);
        } catch (RuntimeException cancellationFailure) {
            return false;
        }
    }

    static CompletionStage<Void> terminationOf(CompletionStage<?> stage) {
        CompletionStage<?> safeStage = Objects.requireNonNull(
                stage,
                "stage cannot be null"
        );
        if (safeStage instanceof CancellableStage cancellable) {
            return Objects.requireNonNull(
                    cancellable.termination(),
                    "termination stage cannot be null"
            );
        }
        return safeStage.handle((ignored, error) -> (Void) null);
    }
}
