package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DialogueActivityReactionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-28T04:00:00Z");

    @Test
    void reviewsEveryStoredDialogueWithoutKeywordFiltering() {
        AtomicInteger decisions = new AtomicInteger();
        AtomicReference<String> observation = new AtomicReference<>();
        DialogueActivityReactionService service = new DialogueActivityReactionService(
                (personId, text, occurredAt) -> {
                    decisions.incrementAndGet();
                    observation.set(text);
                    return CompletableFuture.completedFuture(null);
                },
                Runnable::run
        );

        boolean triggered = service.triggerIfNeeded(
                "去打游戏",
                storedExchange("好呀，我也上号了。")
        );

        assertThat(triggered).isTrue();
        assertThat(decisions).hasValue(1);
        assertThat(observation.get())
                .contains("用户消息：去打游戏")
                .contains("人物回复：")
                .contains("好呀，我也上号了。")
                .contains("只判断数字人物本人")
                .contains("用户本人要做某事不等于人物也在做")
                .hasSizeLessThanOrEqualTo(PersonActivityDecisionService.MAX_OBSERVATION_LENGTH);
    }

    @Test
    void letsTheModelDistinguishUserActivityFromPersonActivity() {
        AtomicReference<String> observation = new AtomicReference<>();
        DialogueActivityReactionService service = new DialogueActivityReactionService(
                (personId, text, occurredAt) -> {
                    observation.set(text);
                    return CompletableFuture.completedFuture(null);
                },
                Runnable::run
        );

        assertThat(service.triggerIfNeeded(
                "我去打游戏了",
                storedExchange("好呀，你去玩吧，我继续画作业。")
        )).isTrue();
        assertThat(observation.get())
                .contains("用户消息：我去打游戏了")
                .contains("好呀，你去玩吧，我继续画作业。")
                .contains("若人物没有明确参与或没有充分证据改变活动，请返回空操作计划");
    }

    @Test
    void serializesReviewsForTheSamePerson() {
        PersonId personId = PersonId.random();
        CompletableFuture<Void> firstDecision = new CompletableFuture<>();
        AtomicInteger invocations = new AtomicInteger();
        DialogueActivityReactionService service = new DialogueActivityReactionService(
                (ignored, observation, occurredAt) -> {
                    int invocation = invocations.incrementAndGet();
                    return invocation == 1
                            ? firstDecision
                            : CompletableFuture.completedFuture(null);
                },
                Runnable::run
        );

        assertThat(service.triggerIfNeeded(
                "一起打游戏吧",
                storedExchange(personId, "好，我上号了。")
        )).isTrue();
        assertThat(service.triggerIfNeeded(
                "你来了吗",
                storedExchange(personId, "来了，正在进房间。")
        )).isTrue();
        assertThat(invocations).hasValue(1);

        firstDecision.complete(null);

        assertThat(invocations).hasValue(2);
    }

    @Test
    void ignoresDialoguesThatWereNotPersisted() {
        AtomicInteger decisions = new AtomicInteger();
        DialogueActivityReactionService service = new DialogueActivityReactionService(
                (personId, observation, occurredAt) -> {
                    decisions.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                Runnable::run
        );

        assertThat(service.triggerIfNeeded("我要睡觉了", failedExchange()))
                .isFalse();
        assertThat(decisions).hasValue(0);
    }

    @Test
    void boundsObservationLengthForOrdinaryDialogue() {
        AtomicReference<String> observation = new AtomicReference<>();
        DialogueActivityReactionService service = new DialogueActivityReactionService(
                (personId, text, occurredAt) -> {
                    observation.set(text);
                    return CompletableFuture.completedFuture(null);
                },
                Runnable::run
        );
        String longReply = "好".repeat(5_000);

        assertThat(service.triggerIfNeeded(
                "你今天过得怎么样？",
                storedExchange(longReply)
        )).isTrue();
        assertThat(observation.get())
                .hasSize(PersonActivityDecisionService.MAX_OBSERVATION_LENGTH);
    }

    private static PersonDialogueExchange storedExchange(String reply) {
        return storedExchange(PersonId.random(), reply);
    }

    private static PersonDialogueExchange storedExchange(PersonId personId, String reply) {
        return new PersonDialogueExchange(
                personId,
                new DialogueResult("", List.of(reply)),
                NOW,
                PersonDialogueExchange.ConversationStatus.STORED,
                2,
                PersonDialogueExchange.MemoryStatus.SCHEDULED,
                0
        );
    }

    private static PersonDialogueExchange failedExchange() {
        return new PersonDialogueExchange(
                PersonId.random(),
                new DialogueResult("", List.of("晚安。")),
                NOW,
                PersonDialogueExchange.ConversationStatus.FAILED,
                0,
                PersonDialogueExchange.MemoryStatus.FAILED,
                0
        );
    }
}
