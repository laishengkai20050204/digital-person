package com.laishengkai.digitalperson.async;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Completable future that also exposes cancellation and physical termination. */
public final class CancellableCompletableFuture<T>
        extends CompletableFuture<T>
        implements CancellableStage {

    private final CompletionStage<Void> termination;
    private final Consumer<Boolean> cancellationAction;

    public CancellableCompletableFuture(
            CompletionStage<Void> termination,
            Consumer<Boolean> cancellationAction
    ) {
        this.termination = Objects.requireNonNull(
                termination,
                "termination cannot be null"
        );
        this.cancellationAction = Objects.requireNonNull(
                cancellationAction,
                "cancellationAction cannot be null"
        );
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!super.cancel(mayInterruptIfRunning)) {
            return false;
        }
        cancellationAction.accept(mayInterruptIfRunning);
        return true;
    }

    @Override
    public boolean requestCancellation(boolean mayInterruptIfRunning) {
        return cancel(mayInterruptIfRunning);
    }

    @Override
    public CompletionStage<Void> termination() {
        return termination;
    }
}
