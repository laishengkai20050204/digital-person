package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * Asynchronously asks the activity decision model to review every persisted real dialogue.
 *
 * <p>The model receives both sides of the completed exchange together with the person's current
 * activity context. It decides whether the person actually started, stopped, continued, or changed
 * an activity. Java remains responsible for validating and applying the resulting lifecycle plan.
 * Reviews are serialized per person so rapid messages cannot race on the same aggregate version.</p>
 */
public final class DialogueActivityReactionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            DialogueActivityReactionService.class
    );

    private final ActivityDecisionTrigger decisionTrigger;
    private final Executor executor;
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
                executor
        );
    }

    DialogueActivityReactionService(ActivityDecisionTrigger decisionTrigger, Executor executor) {
        this.decisionTrigger = Objects.requireNonNull(
                decisionTrigger,
                "decisionTrigger cannot be null"
        );
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
    }

    /**
     * Schedules one semantic activity review for every successfully stored dialogue.
     * Returns whether the review was accepted for asynchronous execution.
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

        String observation = buildObservation(
                normalize(userMessage),
                completed.result(),
                completed.occurredAt()
        );
        try {
            enqueueDecision(
                    completed.personId(),
                    observation,
                    completed.occurredAt()
            );
            return true;
        } catch (RuntimeException error) {
            logFailure(completed.personId(), error);
            return false;
        }
    }

    private void enqueueDecision(
            PersonId personId,
            String observation,
            Instant occurredAt
    ) {
        CompletableFuture<Void> scheduled = personQueues.compute(personId, (ignored, previous) -> {
            CompletionStage<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((result, failure) -> null);
            return ready.thenComposeAsync(
                    result -> decideAsynchronously(personId, observation, occurredAt),
                    executor
            ).toCompletableFuture();
        });
        scheduled.whenComplete((ignored, failure) -> personQueues.remove(personId, scheduled));
    }

    private CompletionStage<Void> decideAsynchronously(
            PersonId personId,
            String observation,
            Instant occurredAt
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
                        "Dialogue-triggered activity review completed: personId={}",
                        personId
                );
            }
            return null;
        });
    }

    private static String buildObservation(
            String userMessage,
            DialogueResult result,
            Instant occurredAt
    ) {
        DialogueResult safeResult = Objects.requireNonNull(result, "result cannot be null");
        List<String> replies = safeResult.replies();
        StringBuilder observation = new StringBuilder(
                "刚完成一轮真实实时对话。请把用户消息与人物回复作为一组新鲜证据，只判断数字人物本人是否实际开始、结束、继续或改变活动；"
                        + "用户本人要做某事不等于人物也在做，讨论、询问、建议、回忆或假设也不等于活动事实。"
                        + "若人物没有明确参与或没有充分证据改变活动，请返回空操作计划。\n\n"
        ).append("对话时间：")
                .append(Objects.requireNonNull(occurredAt, "occurredAt cannot be null"))
                .append("\n用户消息：")
                .append(userMessage)
                .append("\n人物回复：");
        for (String reply : replies) {
            observation.append("\n- ").append(reply);
        }
        if (observation.length() <= PersonActivityDecisionService.MAX_OBSERVATION_LENGTH) {
            return observation.toString();
        }
        return observation.substring(0, PersonActivityDecisionService.MAX_OBSERVATION_LENGTH);
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
}
