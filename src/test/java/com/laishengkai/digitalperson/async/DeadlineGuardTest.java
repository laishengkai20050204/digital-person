package com.laishengkai.digitalperson.async;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlineGuardTest {

    @Test
    void timeoutCancelsTheSourceStage() {
        CompletableFuture<String> source = new CompletableFuture<>();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> DeadlineGuard.within(
                                source,
                                Duration.ofMillis(25),
                                TestTimeoutException::new
                        )
                        .toCompletableFuture()
                        .join()
        );

        assertInstanceOf(TestTimeoutException.class, failure.getCause());
        assertTrue(source.isCancelled());
    }

    @Test
    void callerCancellationPropagatesToTheSourceStage() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> guarded = DeadlineGuard.within(
                source,
                Duration.ofSeconds(5),
                TestTimeoutException::new
        ).toCompletableFuture();

        assertTrue(guarded.cancel(true));
        assertTrue(source.isCancelled());
    }

    @Test
    void physicalTerminationRemainsSeparateFromLogicalTimeout() {
        CompletableFuture<Void> physicalTermination = new CompletableFuture<>();
        CancellableCompletableFuture<String> source = new CancellableCompletableFuture<>(
                physicalTermination,
                ignored -> {
                }
        );
        var guardedStage = DeadlineGuard.within(
                source,
                Duration.ofMillis(25),
                TestTimeoutException::new
        );

        assertThrows(
                CompletionException.class,
                () -> guardedStage.toCompletableFuture().join()
        );
        assertTrue(source.isCancelled());
        CompletableFuture<Void> guardedTermination = ((CancellableStage) guardedStage)
                .termination()
                .toCompletableFuture();
        assertFalse(guardedTermination.isDone());

        physicalTermination.complete(null);
        guardedTermination.join();
        assertTrue(guardedTermination.isDone());
    }

    @Test
    void absoluteDeadlineIsCheckedAgainWhenTheSourceCompletes() {
        Instant now = Instant.parse("2026-07-26T04:00:00Z");
        Instant deadline = now.plusSeconds(30);
        MutableClock clock = new MutableClock(now);
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> guarded = DeadlineGuard.before(
                source,
                deadline,
                clock,
                TestTimeoutException::new
        ).toCompletableFuture();

        clock.set(deadline);
        source.complete("late");

        CompletionException failure = assertThrows(
                CompletionException.class,
                guarded::join
        );
        assertInstanceOf(TestTimeoutException.class, failure.getCause());
    }

    private static final class TestTimeoutException extends RuntimeException {
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        private void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
