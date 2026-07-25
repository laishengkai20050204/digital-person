package com.laishengkai.digitalperson.infrastructure.dialogue;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageModelConversationSummaryModelTest {

    @Test
    void mergesPreviousSummaryAndTimestampedOlderTurns() {
        AtomicReference<LanguageModelRequest> captured = new AtomicReference<>();
        LanguageModelConversationSummaryModel model =
                new LanguageModelConversationSummaryModel(
                        request -> {
                            captured.set(request);
                            return CompletableFuture.completedFuture(
                                    LanguageModelResponse.text(
                                            "用户正在备考，人物答应陪用户复习线性代数。"
                                    )
                            );
                        },
                        JsonMapper.builder().build(),
                        new PersonDialogueProperties(
                                8,
                                12,
                                900,
                                0.6,
                                true,
                                8,
                                700,
                                0.1
                        )
                );

        String summary = model.summarize(
                Optional.of("用户最近开始准备考试。"),
                List.of(
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.USER,
                                "我今晚复习线性代数。",
                                Instant.parse("2026-07-25T02:00:00Z")
                        ),
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.PERSON,
                                "好，我陪你。",
                                Instant.parse("2026-07-25T02:00:05Z")
                        )
                ),
                ZoneId.of("Asia/Shanghai")
        ).toCompletableFuture().join();

        assertThat(summary).isEqualTo("用户正在备考，人物答应陪用户复习线性代数。");
        assertThat(captured.get().messages()).hasSize(2);
        assertThat(captured.get().messages().getFirst())
                .isInstanceOf(SystemModelMessage.class);
        assertThat(captured.get().messages().get(1))
                .isInstanceOf(UserModelMessage.class);
        assertThat(((UserModelMessage) captured.get().messages().get(1)).text())
                .contains("用户最近开始准备考试。")
                .contains("2026-07-25 10:00:00 +08:00 Asia/Shanghai")
                .contains("我今晚复习线性代数。")
                .contains("好，我陪你。");
        assertThat(captured.get().options().maxOutputTokens()).isEqualTo(700);
        assertThat(captured.get().options().temperature()).isEqualTo(0.1);
        assertThat(captured.get().options().toolChoice()).isEqualTo(ModelToolChoice.NONE);
        assertThat(captured.get().tools()).isEmpty();
    }
}
