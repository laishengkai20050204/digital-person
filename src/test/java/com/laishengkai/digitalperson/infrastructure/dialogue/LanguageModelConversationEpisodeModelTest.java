package com.laishengkai.digitalperson.infrastructure.dialogue;

import com.laishengkai.digitalperson.conversation.ConversationEpisodeDraft;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelResponseFormat;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageModelConversationEpisodeModelTest {

    @Test
    void extractsACompleteEpisodeWithTimestampedInput() {
        AtomicReference<LanguageModelRequest> captured = new AtomicReference<>();
        LanguageModelConversationEpisodeModel model = model(captured, """
                {
                  "episodes": [
                    {
                      "title": "用户决定调整游戏搭子相处方式",
                      "summary": "用户因对方临时转去玩 Steam 感到被忽视，随后讨论了感受和边界。",
                      "eventType": "CONFLICT",
                      "participants": ["用户", "游戏搭子"],
                      "emotions": ["失落", "不安全感"],
                      "outcome": "用户决定简短表达感受，不再反复争论。",
                      "importance": 0.82
                    }
                  ]
                }
                """);

        List<ConversationEpisodeDraft> episodes = model.extract(
                List.of(
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.USER,
                                "她临时去玩 Steam，我觉得自己被当外人。",
                                Instant.parse("2026-07-18T12:00:00Z")
                        ),
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.PERSON,
                                "可以简短表达感受，然后观察她后续行动。",
                                Instant.parse("2026-07-18T12:05:00Z")
                        )
                ),
                ZoneId.of("Asia/Shanghai")
        ).toCompletableFuture().join();

        assertThat(episodes).hasSize(1);
        assertThat(episodes.getFirst().eventType()).isEqualTo("CONFLICT");
        assertThat(episodes.getFirst().importance()).isEqualTo(0.82);
        assertThat(episodes.getFirst().participants())
                .containsExactly("用户", "游戏搭子");
        assertThat(captured.get().messages()).hasSize(2);
        assertThat(((UserModelMessage) captured.get().messages().get(1)).text())
                .contains("2026-07-18 20:00:00 +08:00 Asia/Shanghai")
                .contains("她临时去玩 Steam")
                .contains("观察她后续行动");
        assertThat(captured.get().options().temperature()).isEqualTo(0.08);
        assertThat(captured.get().options().maxOutputTokens()).isEqualTo(650);
        assertThat(captured.get().options().toolChoice()).isEqualTo(ModelToolChoice.NONE);
        assertThat(captured.get().options().responseFormat().type())
                .isEqualTo(ModelResponseFormat.Type.JSON_OBJECT);
    }

    @Test
    void rejectsTemporaryTestCodeEpisodesEvenWhenTheModelReturnsOne() {
        LanguageModelConversationEpisodeModel model = model(new AtomicReference<>(), """
                {
                  "episodes": [
                    {
                      "title": "记录测试码",
                      "summary": "滚动摘要临时测试码是 Q7M4K2。",
                      "eventType": "OTHER",
                      "participants": ["用户"],
                      "emotions": [],
                      "outcome": "技术测试完成。",
                      "importance": 0.9
                    }
                  ]
                }
                """);

        List<ConversationEpisodeDraft> episodes = model.extract(
                List.of(new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "测试码 Q7M4K2",
                        Instant.EPOCH
                )),
                ZoneId.of("UTC")
        ).toCompletableFuture().join();

        assertThat(episodes).isEmpty();
    }

    private static LanguageModelConversationEpisodeModel model(
            AtomicReference<LanguageModelRequest> captured,
            String response
    ) {
        return new LanguageModelConversationEpisodeModel(
                request -> {
                    captured.set(request);
                    return CompletableFuture.completedFuture(
                            LanguageModelResponse.text(response)
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
                        0.1,
                        true,
                        650,
                        0.08
                )
        );
    }
}
