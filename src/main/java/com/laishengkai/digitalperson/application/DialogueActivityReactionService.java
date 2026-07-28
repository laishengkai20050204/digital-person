package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Conservatively reacts to explicit activity-changing dialogue signals after persistence.
 *
 * <p>The deterministic filter avoids an extra model call for ordinary conversation. A matched
 * turn is delegated to the existing autonomous activity decision boundary, which remains
 * responsible for validating and applying any lifecycle changes.</p>
 */
public final class DialogueActivityReactionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            DialogueActivityReactionService.class
    );

    private static final String ACTIVITY_TERMS =
            "(?:睡觉|睡一会儿?|休息|起床|吃饭|早餐|午饭|晚饭|夜宵|上课|下课|学习|复习|"
                    + "写作业|做作业|工作|上班|下班|开会|运动|跑步|健身|散步|出门|回家|"
                    + "洗澡|打游戏|玩游戏|打王者|王者|看电影|看剧|追剧|听歌|听音乐|聊天|"
                    + "打电话|视频|逛街|购物|做饭|收拾|画画|设计)";

    private static final Pattern EXPLICIT_TRANSITION = Pattern.compile(
            "(?:我要|我去|我准备|我打算|我先|我得|我该|我想|准备|打算|开始|继续|停止|"
                    + "结束|暂停|先不|不玩了|不聊了|不学了|不工作了).{0,24}"
                    + ACTIVITY_TERMS
    );
    private static final Pattern EXPLICIT_INVITATION = Pattern.compile(
            "(?:我们(?:来|去|一起)?|一起|陪我).{0,24}" + ACTIVITY_TERMS
    );
    private static final Pattern DIRECT_PERSON_REQUEST = Pattern.compile(
            "你(?:先|也|来|去).{0,20}" + ACTIVITY_TERMS + ".{0,8}(?:吧|好吗|好不好)"
    );
    private static final Pattern ACTIVITY_SUFFIX = Pattern.compile(
            ACTIVITY_TERMS + ".{0,8}(?:开始吧|继续吧|结束吧|停止吧|暂停吧|去吧|吧)$"
    );
    private static final Pattern QUESTION_ENDING = Pattern.compile("(?:吗|么|嘛|呢|？|\\?)$");

    private final ActivityDecisionTrigger decisionTrigger;
    private final Executor executor;

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
     * Schedules an activity review only for a successfully stored dialogue with a strong signal.
     * Returns whether a review was accepted for asynchronous execution.
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

        String normalizedMessage = normalize(userMessage);
        if (!requiresActivityReview(normalizedMessage)) {
            return false;
        }

        String observation = buildObservation(
                normalizedMessage,
                completed.result(),
                completed.occurredAt()
        );
        try {
            executor.execute(() -> decideAsynchronously(
                    completed.personId(),
                    observation,
                    completed.occurredAt()
            ));
            return true;
        } catch (RuntimeException error) {
            logFailure(completed.personId(), error);
            return false;
        }
    }

    static boolean requiresActivityReview(String message) {
        String normalized = normalize(message);
        if (EXPLICIT_INVITATION.matcher(normalized).find()
                || DIRECT_PERSON_REQUEST.matcher(normalized).find()) {
            return true;
        }
        if (QUESTION_ENDING.matcher(normalized).find()) {
            return false;
        }
        return EXPLICIT_TRANSITION.matcher(normalized).find()
                || ACTIVITY_SUFFIX.matcher(normalized).find();
    }

    private void decideAsynchronously(
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
            return;
        }
        stage.whenComplete((ignored, failure) -> {
            if (failure != null) {
                logFailure(personId, unwrap(failure));
                return;
            }
            LOGGER.info(
                    "Dialogue-triggered activity review completed: personId={}",
                    personId
            );
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
                "刚完成一轮实时对话。请结合人物当前活动、状态和以下对话，判断是否需要开始、结束或调整活动；"
                        + "若无需改变，请返回空操作计划。\n\n"
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
