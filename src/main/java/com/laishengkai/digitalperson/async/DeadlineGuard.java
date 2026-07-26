package com.laishengkai.digitalperson.async;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Applies one consistent timeout/deadline and cancellation policy to stages. */
public final class DeadlineGuard {
    private static final ThreadFactory TIMER_THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "digital-person-deadline-timer");
        thread.setDaemon(true);
        return thread;
    };
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();


    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(
                1,
                TIMER_THREAD_FACTORY
        );
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return timer;
    }

    private DeadlineGuard() {
    }

    public static <T> CompletionStage<T> within(
            CompletionStage<T> stage,
            Duration timeout,
            Supplier<? extends RuntimeException> timeoutException
    ) {
        Duration safeTimeout = requirePositive(timeout, "timeout");
        return guard(
                stage,
                safeTimeout,
                () -> false,
                timeoutException
        );
    }

    public static <T> CompletionStage<T> before(
            CompletionStage<T> stage,
            Instant deadline,
            Clock clock,
            Supplier<? extends RuntimeException> timeoutException
    ) {
        CompletionStage<T> safeStage = Objects.requireNonNull(
                stage,
                "stage cannot be null"
        );
        Instant safeDeadline = Objects.requireNonNull(
                deadline,
                "deadline cannot be null"
        );
        Clock safeClock = Objects.requireNonNull(clock, "clock cannot be null");
        Supplier<? extends RuntimeException> safeException = requireExceptionSupplier(
                timeoutException
        );

        if (Instant.MAX.equals(safeDeadline)) {
            return guardWithoutTimer(
                    safeStage,
                    () -> !safeClock.instant().isBefore(safeDeadline),
                    safeException
            );
        }

        Duration remaining = Duration.between(safeClock.instant(), safeDeadline);
        if (remaining.isZero() || remaining.isNegative()) {
            CancellableStage.cancel(safeStage, true);
            return CompletableFuture.failedFuture(newTimeout(safeException));
        }
        return guard(
                safeStage,
                remaining,
                () -> !safeClock.instant().isBefore(safeDeadline),
                safeException
        );
    }

    private static <T> CompletionStage<T> guard(
            CompletionStage<T> stage,
            Duration timeout,
            BooleanSupplier expired,
            Supplier<? extends RuntimeException> timeoutException
    ) {
        CompletionStage<T> safeStage = Objects.requireNonNull(
                stage,
                "stage cannot be null"
        );
        Duration safeTimeout = requirePositive(timeout, "timeout");
        BooleanSupplier safeExpired = Objects.requireNonNull(
                expired,
                "expired cannot be null"
        );
        Supplier<? extends RuntimeException> safeException = requireExceptionSupplier(
                timeoutException
        );
        AtomicBoolean settled = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> timerReference = new AtomicReference<>();
        CompletionStage<Void> termination = CancellableStage.terminationOf(safeStage);
        CancellableCompletableFuture<T> guarded = new CancellableCompletableFuture<>(
                termination,
                mayInterrupt -> {
                    if (settled.compareAndSet(false, true)) {
                        cancelTimer(timerReference.get());
                        CancellableStage.cancel(safeStage, mayInterrupt);
                    }
                }
        );

        long timeoutNanos = toNanosSaturated(safeTimeout);
        ScheduledFuture<?> timer = TIMER.schedule(() -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            CancellableStage.cancel(safeStage, true);
            guarded.completeExceptionally(newTimeout(safeException));
        }, timeoutNanos, TimeUnit.NANOSECONDS);
        timerReference.set(timer);

        safeStage.whenComplete((value, error) -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            cancelTimer(timerReference.get());
            if (error != null) {
                guarded.completeExceptionally(unwrap(error));
                return;
            }
            if (safeExpired.getAsBoolean()) {
                guarded.completeExceptionally(newTimeout(safeException));
                return;
            }
            guarded.complete(value);
        });
        return guarded;
    }

    private static <T> CompletionStage<T> guardWithoutTimer(
            CompletionStage<T> stage,
            BooleanSupplier expired,
            Supplier<? extends RuntimeException> timeoutException
    ) {
        AtomicBoolean settled = new AtomicBoolean();
        CancellableCompletableFuture<T> guarded = new CancellableCompletableFuture<>(
                CancellableStage.terminationOf(stage),
                mayInterrupt -> {
                    if (settled.compareAndSet(false, true)) {
                        CancellableStage.cancel(stage, mayInterrupt);
                    }
                }
        );
        stage.whenComplete((value, error) -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            if (error != null) {
                guarded.completeExceptionally(unwrap(error));
            } else if (expired.getAsBoolean()) {
                guarded.completeExceptionally(newTimeout(timeoutException));
            } else {
                guarded.complete(value);
            }
        });
        return guarded;
    }

    private static Supplier<? extends RuntimeException> requireExceptionSupplier(
            Supplier<? extends RuntimeException> supplier
    ) {
        return Objects.requireNonNull(supplier, "timeoutException cannot be null");
    }

    private static RuntimeException newTimeout(
            Supplier<? extends RuntimeException> supplier
    ) {
        return Objects.requireNonNull(
                supplier.get(),
                "timeoutException cannot return null"
        );
    }

    private static Duration requirePositive(Duration value, String fieldName) {
        Duration duration = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        );
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return duration;
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return Math.max(1L, duration.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static void cancelTimer(ScheduledFuture<?> timer) {
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = Objects.requireNonNull(error, "error cannot be null");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
