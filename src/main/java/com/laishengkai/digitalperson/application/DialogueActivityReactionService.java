package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronously asks the activity decision model to review persisted real dialogue.
 *
 * <p>Rapid exchanges are coalesced into one semantic review after a short quiet period. A maximum
 * batch wait prevents continuous chat from postponing review indefinitely. The model receives both
 * sides of every exchange in chronological order and decides whether the person actually started,
 * stopped, continued, or changed an activity. Java remains responsible for validating and applying
 * the lifecycle plan. Reviews are also serialized per person so batches cannot race on the same
 * aggregate version.</p>
 */
public final class DialogueActivityReactionService {
    static final Duration DEFAULT_DEBOUNCE_WINDOW = Duration.ofSeconds(2);
    static final Duration DEFAULT_MAX_BATCH_WAIT = Duration.ofSeconds(8);

    private static final Logger LOGGER = LoggerFactory.getLogger(
            DialogueActivityReactionService.class
    );

    private final ActivityDecisionTrigger decisionTrigger;
    private final Executor executor;
    private final Duration debounceWindow;
    private final Duration maximumBatchWait;
    private final DelayScheduler delayScheduler;
    private final ConcurrentMap<PersonId, PendingBatch> pendingBatches =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<PersonId, CompletableFuture<Void>> personQueues =
            new ConcurrentHashMap<>();

    public DialogueActivityReactionService(
            PersonActivityDecisionService decisionService,
            Executor executor
    ) {
        this(
                (personId, observation, occurredAt) -> decisionService.decide(
                        personId,
                        observation,
                        occurredAt
                ),
                executor,
                DEFAULT_DEBOUNCE_WINDOW,
                DEFAULT_MAX_BATCH_WAIT,
                delayedScheduler(executor)
        );
    }

    /** Immediate scheduling constructor retained for deterministic unit-level collaborators. */
    DialogueActivityReactionService(ActivityDecisionTrigger decisionTrigger, Executor executor) {
        this(
                decisionTrigger,
                executor,
                Duration.ZERO,
                Duration.ZERO,
                (task, ignored) -> {
                    task.run();
                    return CompletableFuture.completedFuture(null);
                }
        );
    }

    DialogueActivityReactionService(
            ActivityDecisionTrigger decisionTrigger,
            Executor executor,
            Duration debounceWindow,
            Duration maximumBatchWait,
            DelayScheduler delayScheduler
    ) {
        this.decisionTrigger = Objects.requireNonNull(
                decisionTrigger,
                "decisionTrigger cannot be null"
        );
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        this.debounceWindow = requireNonNegative(debounceWindow, "debounceWindow");
        this.maximumBatchWait = requireNonNegative(maximumBatchWait, "maximumBatchWait");
        if (this.maximumBatchWait.compareTo(this.debounceWindow) < 0) {
            throw new IllegalArgumentException(
                    "maximumBatchWait cannot be shorter than debounceWindow"
            );
        }
        this.delayScheduler = Objects.requireNonNull(
                delayScheduler,
                "delayScheduler cannot be null"
        );
    }

    /**
     * Adds every successfully stored dialogue to a semantic review batch.
     * Returns whether batching was accepted for asynchronous execution.
     */
    public boolean triggerIfNeeded(String userMessage, PersonDialogueExchange exchange) {
        PersonDialogueExchange completed = Objects.requireNonNull(
                exchange,
                "exchange cannot be null"
        );
        if (completed.conversationStatus()
                != PersonDialogueExchange.ConversationStatus.STORED) {
            return false;
        }

        DialogueEvidence evidence = new DialogueEvidence(
                normalize(userMessage),
                completed.result().replies(),
                completed.occurredAt()
        );
        BatchRegistration registration = new BatchRegistration();
        try {
            pendingBatches.compute(completed.personId(), (ignored, current) -> {
                PendingBatch batch = current;
                if (batch == null) {
                    batch = new PendingBatch();
                    registration.created = true;
                }
                batch.evidence.add(evidence);
                registration.batch = batch;
                registration.generation = ++batch.generation;
                return batch;
            });

            scheduleFlush(
                    completed.personId(),
                    registration.batch,
                    registration.generation,
                    debounceWindow,
                    false,
                    true
            );
            if (registration.created) {
                scheduleFlush(
                        completed.personId(),
                        registration.batch,
                        registration.generation,
                        maximumBatchWait,
                        true,
                        false
                );
            }
            return true;
        } catch (RuntimeException error) {
            logFailure(completed.personId(), error);
            flushBatch(
                    completed.personId(),
                    registration.batch,
                    registration.generation,
                    true
            );
            return registration.batch != null;
        }
    }

    private void scheduleFlush(
            PersonId personId,
            PendingBatch batch,
            long generation,
            Duration delay,
            boolean force,
            boolean flushOnSchedulingFailure
    ) {
        final CompletionStage<Void> scheduled;
        try {
            scheduled = Objects.requireNonNull(
                    delayScheduler.schedule(
                            () -> flushBatch(personId, batch, generation, force),
                            delay
                    ),
                    "delay scheduler stage cannot be null"
            );
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Could not schedule dialogue activity batch flush: personId={}, delayMs={}",
                    personId,
                    delay.toMillis(),
                    error
            );
            if (flushOnSchedulingFailure) {
                flushBatch(personId, batch, generation, true);
            }
            return;
        }
        scheduled.whenComplete((ignored, failure) -> {
            if (failure == null) {
                return;
            }
            LOGGER.warn(
                    "Dialogue activity batch timer failed: personId={}, delayMs={}",
                    personId,
                    delay.toMillis(),
                    unwrap(failure)
            );
            if (flushOnSchedulingFailure) {
                flushBatch(personId, batch, generation, true);
            }
        });
    }

    private void flushBatch(
            PersonId personId,
            PendingBatch expectedBatch,
            long expectedGeneration,
            boolean force
    ) {
        if (expectedBatch == null) {
            return;
        }
        AtomicReference<BatchSnapshot> snapshotReference = new AtomicReference<>();
        pendingBatches.compute(personId, (ignored, current) -> {
            if (current != expectedBatch) {
                return current;
            }
            if (!force && current.generation != expectedGeneration) {
                return current;
            }
            snapshotReference.set(new BatchSnapshot(List.copyOf(current.evidence)));
            return null;
        });

        BatchSnapshot snapshot = snapshotReference.get();
        if (snapshot == null || snapshot.evidence().isEmpty()) {
            return;
        }
        String observation = buildObservation(snapshot.evidence());
        LOGGER.info(
                "Dialogue activity review batch queued: personId={}, exchangeCount={}, debounceMs={}",
                personId,
                snapshot.evidence().size(),
                debounceWindow.toMillis()
        );
        enqueueDecision(
                personId,
                observation,
                snapshot.latestOccurredAt(),
                snapshot.evidence().size()
        );
    }

    private void enqueueDecision(
            PersonId personId,
            String observation,
            Instant occurredAt,
            int exchangeCount
    ) {
        CompletableFuture<Void> scheduled = personQueues.compute(personId, (ignored, previous) -> {
            CompletionStage<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((result, failure) -> null);
            return ready.thenComposeAsync(
                    result -> decideAsynchronously(
                            personId,
                            observation,
                            occurredAt,
                            exchangeCount
                    ),
                    executor
            ).toCompletableFuture();
        });
        scheduled.whenComplete((ignored, failure) -> personQueues.remove(personId, scheduled));
    }

    private CompletionStage<Void> decideAsynchronously(
            PersonId personId,
            String observation,
            Instant occurredAt,
            int exchangeCount
    ) {
        final CompletionStage<?> stage;
        try {
            stage = Objects.requireNonNull(
                    decisionTrigger.decide(personId, observation, occurredAt),
                    "activity decision stage cannot be null"
            );
        } catch (RuntimeException error) {
            logFailure(personId, error);
            return CompletableFuture.completedFuture(null);
        }
        return stage.handle((ignored, failure) -> {
            if (failure != null) {
                logFailure(personId, unwrap(failure));
            } else {
                LOGGER.info(
                        "Dialogue-triggered activity review completed: "
                                + "personId={}, exchangeCount={}",
                        personId,
                        exchangeCount
                );
            }
            return null;
        });
    }

    private static String buildObservation(List<DialogueEvidence> evidence) {
        List<DialogueEvidence> safeEvidence = List.copyOf(
                Objects.requireNonNull(evidence, "evidence cannot be null")
        );
        if (safeEvidence.isEmpty()) {
            throw new IllegalArgumentException("evidence cannot be empty");
        }

        String header = "刚完成一组连续真实实时对话，共" + safeEvidence.size()
                + "轮。请按时间顺序把用户消息与人物回复作为一组新鲜证据，只判断数字人物本人是否实际开始、结束、继续或改变活动；"
                + "用户本人要做某事不等于人物也在做，讨论、询问、建议、回忆或假设也不等于活动事实。"
                + "若人物没有明确参与或没有充分证据改变活动，请返回空操作计划。\n";
        int maximumLength = PersonActivityDecisionService.MAX_OBSERVATION_LENGTH;
        if (header.length() >= maximumLength) {
            return header.substring(0, maximumLength);
        }

        int remaining = maximumLength - header.length();
        List<String> selectedBlocks = new ArrayList<>();
        for (int index = safeEvidence.size() - 1; index >= 0; index--) {
            String block = formatEvidence(index + 1, safeEvidence.get(index));
            if (block.length() <= remaining) {
                selectedBlocks.addFirst(block);
                remaining -= block.length();
                continue;
            }
            if (selectedBlocks.isEmpty() && remaining > 0) {
                selectedBlocks.addFirst(block.substring(0, remaining));
            }
            break;
        }

        StringBuilder observation = new StringBuilder(header);
        for (String block : selectedBlocks) {
            observation.append(block);
        }
        return observation.toString();
    }

    private static String formatEvidence(int sequence, DialogueEvidence evidence) {
        StringBuilder block = new StringBuilder("\n对话")
                .append(sequence)
                .append("：\n时间：")
                .append(evidence.occurredAt())
                .append("\n用户消息：")
                .append(evidence.userMessage())
                .append("\n人物回复：");
        for (String reply : evidence.replies()) {
            block.append("\n- ").append(reply);
        }
        return block.toString();
    }

    private static DelayScheduler delayedScheduler(Executor executor) {
        Executor safeExecutor = Objects.requireNonNull(executor, "executor cannot be null");
        return (task, delay) -> CompletableFuture.runAsync(
                task,
                CompletableFuture.delayedExecutor(
                        delay.toMillis(),
                        TimeUnit.MILLISECONDS,
                        safeExecutor
                )
        );
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Duration safeValue = Objects.requireNonNull(value, name + " cannot be null");
        if (safeValue.isNegative()) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
        return safeValue;
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "userMessage cannot be null").strip();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void logFailure(PersonId personId, Throwable error) {
        LOGGER.warn(
                "Dialogue-triggered activity review failed after reply generation: personId={}",
                personId,
                error
        );
    }

    @FunctionalInterface
    interface ActivityDecisionTrigger {
        CompletionStage<?> decide(PersonId personId, String observation, Instant occurredAt);
    }

    @FunctionalInterface
    interface DelayScheduler {
        CompletionStage<Void> schedule(Runnable task, Duration delay);
    }

    private static final class PendingBatch {
        private final List<DialogueEvidence> evidence = new ArrayList<>();
        private long generation;
    }

    private static final class BatchRegistration {
        private PendingBatch batch;
        private long generation;
        private boolean created;
    }

    private record BatchSnapshot(List<DialogueEvidence> evidence) {
        private BatchSnapshot {
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence cannot be null"));
            if (evidence.isEmpty()) {
                throw new IllegalArgumentException("evidence cannot be empty");
            }
        }

        private Instant latestOccurredAt() {
            return evidence.getLast().occurredAt();
        }
    }

    private record DialogueEvidence(
            String userMessage,
            List<String> replies,
            Instant occurredAt
    ) {
        private DialogueEvidence {
            userMessage = Objects.requireNonNull(userMessage, "userMessage cannot be null");
            replies = List.copyOf(Objects.requireNonNull(replies, "replies cannot be null"));
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        }
    }
}
