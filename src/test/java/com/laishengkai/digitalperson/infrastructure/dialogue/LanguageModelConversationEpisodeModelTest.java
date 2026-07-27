package com.laishengkai.digitalperson.infrastructure.dialogue;

import com.laishengkai.digitalperson.conversation.ConversationEpisodeDraft;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelFinishReason;
import com.laishengkai.digitalperson.dialogue.ModelResponseFormat;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.ModelUsage;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageModelConversationEpisodeModelTest {

    @Test
    void extractsACompleteEpisodeWithRequiredSubmissionToolAndTimestampedInput() {
        SequenceGateway gateway = new SequenceGateway(toolResponse(validSubmission()));
        LanguageModelConversationEpisodeModel model = model(gateway, 2048);

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

        LanguageModelRequest request = gateway.requests().getFirst();
        assertThat(request.messages()).hasSize(2);
        assertThat(((UserModelMessage) request.messages().get(1)).text())
                .contains("2026-07-18 20:00:00 +08:00 Asia/Shanghai")
                .contains("她临时去玩 Steam")
                .contains("观察她后续行动");
        assertThat(request.options().temperature()).isEqualTo(0.08);
        assertThat(request.options().maxOutputTokens()).isEqualTo(2048);
        assertThat(request.options().toolChoice()).isEqualTo(ModelToolChoice.REQUIRED);
        assertThat(request.options().responseFormat().type())
                .isEqualTo(ModelResponseFormat.Type.TEXT);
        assertThat(request.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo(
                    LanguageModelConversationEpisodeModel.TOOL_NAME
            );
            assertThat(tool.parametersJsonSchema())
                    .contains("\"required\":[\"episodes\"]")
                    .contains("\"maxItems\":4")
                    .contains("\"additionalProperties\":false");
        });
    }

    @Test
    void retriesExactlyOnceWhenFirstSubmissionOmitsEpisodesArray() {
        SequenceGateway gateway = new SequenceGateway(
                toolResponse("{}"),
                toolResponse("{\"episodes\":[]}")
        );
        LanguageModelConversationEpisodeModel model = model(gateway, 2048);

        List<ConversationEpisodeDraft> episodes = model.extract(
                List.of(new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "普通寒暄。",
                        Instant.EPOCH
                )),
                ZoneId.of("UTC")
        ).toCompletableFuture().join();

        assertThat(episodes).isEmpty();
        assertThat(gateway.requests()).hasSize(2);
        SystemModelMessage retrySystem = (SystemModelMessage) gateway.requests()
                .get(1)
                .messages()
                .getFirst();
        assertThat(retrySystem.text())
                .contains("未通过 Java 校验")
                .contains("必须包含 episodes 数组")
                .contains("不得输出普通文字");
    }

    @Test
    void retriesExactlyOnceWhenFirstResponseDoesNotCallSubmissionTool() {
        SequenceGateway gateway = new SequenceGateway(
                LanguageModelResponse.text("{\"episodes\":[]}"),
                toolResponse("{\"episodes\":[]}")
        );
        LanguageModelConversationEpisodeModel model = model(gateway, 2048);

        List<ConversationEpisodeDraft> episodes = model.extract(
                List.of(new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "普通消息。",
                        Instant.EPOCH
                )),
                ZoneId.of("UTC")
        ).toCompletableFuture().join();

        assertThat(episodes).isEmpty();
        assertThat(gateway.requests()).hasSize(2);
    }

    @Test
    void acceptsAnExplicitEmptyEpisodeSubmission() {
        SequenceGateway gateway = new SequenceGateway(toolResponse("{\"episodes\":[]}"));
        LanguageModelConversationEpisodeModel model = model(gateway, 2048);

        List<ConversationEpisodeDraft> episodes = model.extract(
                List.of(new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "你好。",
                        Instant.EPOCH
                )),
                ZoneId.of("UTC")
        ).toCompletableFuture().join();

        assertThat(episodes).isEmpty();
        assertThat(gateway.requests()).hasSize(1);
    }

    @Test
    void rejectsTemporaryTestCodeEpisodesEvenWhenTheModelSubmitsOne() {
        SequenceGateway gateway = new SequenceGateway(toolResponse("""
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
                """));
        LanguageModelConversationEpisodeModel model = model(gateway, 2048);

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
            LanguageModelGateway gateway,
            int maxOutputTokens
    ) {
        return new LanguageModelConversationEpisodeModel(
                gateway,
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
                        maxOutputTokens,
                        0.08
                )
        );
    }

    private static LanguageModelResponse toolResponse(String argumentsJson) {
        return new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(new ModelToolCall(
                        "call-1",
                        LanguageModelConversationEpisodeModel.TOOL_NAME,
                        argumentsJson
                ))),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unknown()
        );
    }

    private static String validSubmission() {
        return """
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
                """;
    }

    private static final class SequenceGateway implements LanguageModelGateway {
        private final ArrayDeque<LanguageModelResponse> responses;
        private final List<LanguageModelRequest> requests = new ArrayList<>();

        private SequenceGateway(LanguageModelResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public CompletionStage<LanguageModelResponse> invoke(LanguageModelRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(responses.removeFirst());
        }

        private List<LanguageModelRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
