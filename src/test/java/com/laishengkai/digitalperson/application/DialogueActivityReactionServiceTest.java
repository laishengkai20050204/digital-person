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
        return new PersonDialogueExchange(
                PersonId.random(),
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
