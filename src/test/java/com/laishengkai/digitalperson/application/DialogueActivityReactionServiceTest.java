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
    void triggersOnlyForExplicitActivityChangingDialogue() {
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
                "我要睡觉了",
                storedExchange("好呀，那你早点休息。")
        );

        assertThat(triggered).isTrue();
        assertThat(decisions).hasValue(1);
        assertThat(observation.get())
                .contains("用户消息：我要睡觉了")
                .contains("人物回复：")
                .contains("好呀，那你早点休息。")
                .hasSizeLessThanOrEqualTo(PersonActivityDecisionService.MAX_OBSERVATION_LENGTH);
    }

    @Test
    void ignoresOrdinaryQuestionsAndUnstoredDialogues() {
        AtomicInteger decisions = new AtomicInteger();
        DialogueActivityReactionService service = new DialogueActivityReactionService(
                (personId, observation, occurredAt) -> {
                    decisions.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                Runnable::run
        );

        assertThat(service.triggerIfNeeded("你在干嘛", storedExchange("在画作业。")))
                .isFalse();
        assertThat(service.triggerIfNeeded("你在吃饭吗？", storedExchange("还没有。")))
                .isFalse();
        assertThat(service.triggerIfNeeded("哈哈", storedExchange("笑什么呀。")))
                .isFalse();
        assertThat(service.triggerIfNeeded("我要睡觉了", failedExchange()))
                .isFalse();
        assertThat(decisions).hasValue(0);
    }

    @Test
    void recognizesInvitationsAndBoundsObservationLength() {
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
                "我们一起打王者吗？",
                storedExchange(longReply)
        )).isTrue();
        assertThat(observation.get())
                .hasSize(PersonActivityDecisionService.MAX_OBSERVATION_LENGTH);
    }

    @Test
    void exposesConservativeSignalDetection() {
        assertThat(DialogueActivityReactionService.requiresActivityReview("陪我聊一会儿"))
                .isTrue();
        assertThat(DialogueActivityReactionService.requiresActivityReview("我准备去上课了"))
                .isTrue();
        assertThat(DialogueActivityReactionService.requiresActivityReview("你去吃饭了吗？"))
                .isFalse();
        assertThat(DialogueActivityReactionService.requiresActivityReview("今天上课累吗？"))
                .isFalse();
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
