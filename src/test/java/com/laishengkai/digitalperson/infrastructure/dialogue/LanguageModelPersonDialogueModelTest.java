package com.laishengkai.digitalperson.infrastructure.dialogue;

import com.laishengkai.digitalperson.application.DefaultPersonModelContextAssembler;
import com.laishengkai.digitalperson.application.PersonModelContextAssemblyRequest;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.dialogue.AssistantModelMessage;
import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.PersonDialogueException;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import com.laishengkai.digitalperson.modelcontext.PersonModelContextSnapshot;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.personality.Personality;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageModelPersonDialogueModelTest {

    @Test
    void sendsSummaryThenEpisodesThenTimestampedNativeRecentMessages() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        var baseContext = DefaultPersonModelContextAssembler.withoutExternalSources()
                .assemble(
                        person,
                        person.getStateSnapshot(),
                        person.getStateEvolutionContext(),
                        new PersonModelContextAssemblyRequest(
                                Set.of(),
                                "用户喜欢什么电影",
                                true,
                                8,
                                12
                        ),
                        Instant.parse("2026-07-25T01:00:00Z")
                )
                .toCompletableFuture()
                .join();
        PersonModelContextSnapshot context = withRecentConversation(
                baseContext,
                List.of(
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.USER,
                                "我今天晚上准备复习线性代数。",
                                Instant.parse("2026-07-25T00:58:00Z")
                        ),
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.PERSON,
                                "好呀，我陪你。",
                                Instant.parse("2026-07-25T00:59:00Z")
                        ),
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.SYSTEM,
                                "会话切换设备",
                                Instant.parse("2026-07-25T00:59:30Z")
                        ),
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.EPISODE,
                                "事件：用户调整游戏搭子相处方式\n经过：用户在冲突后决定简短表达感受。",
                                Instant.parse("2026-07-18T12:05:00Z")
                        ),
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.SUMMARY,
                                "用户此前在准备考试，人物答应陪伴复习。",
                                Instant.parse("2026-07-25T00:57:00Z")
                        )
                )
        );
        AtomicReference<LanguageModelRequest> captured = new AtomicReference<>();
        LanguageModelPersonDialogueModel model = new LanguageModelPersonDialogueModel(
                request -> {
                    captured.set(request);
                    return CompletableFuture.completedFuture(
                            LanguageModelResponse.text("记得，你喜欢科幻片。")
                    );
                },
                JsonMapper.builder().build(),
                new PersonDialogueProperties(8, 12, 900, 0.6)
        );

        DialogueResult result = model.reply(
                context,
                "你还记得我喜欢什么电影吗？"
        ).toCompletableFuture().join();

        assertThat(result.replies()).containsExactly("记得，你喜欢科幻片。");
        assertThat(captured.get().messages()).hasSize(7);
        assertThat(captured.get().messages().stream()
                .filter(SystemModelMessage.class::isInstance))
                .hasSize(1);
        assertThat(captured.get().messages().getFirst())
                .isInstanceOf(SystemModelMessage.class);
        assertThat(((SystemModelMessage) captured.get().messages().getFirst()).text())
                .contains("context_json")
                .contains("最后一条 user 消息中的 <current_user_message> 标签")
                .contains("本轮唯一需要直接回应的内容")
                .contains("只回答指定内容")
                .contains(person.getId().toString())
                .contains("\"recentConversation\":[]")
                .doesNotContain("我今天晚上准备复习线性代数。")
                .doesNotContain("用户此前在准备考试")
                .doesNotContain("用户调整游戏搭子相处方式");
        assertThat(((UserModelMessage) captured.get().messages().get(1)).text())
                .contains("较早对话滚动摘要")
                .contains("2026-07-25 00:57:00 Z")
                .contains("用户此前在准备考试，人物答应陪伴复习。")
                .contains("仅作为背景数据");
        assertThat(((UserModelMessage) captured.get().messages().get(2)).text())
                .contains("历史事件记忆")
                .contains("2026-07-18 12:05:00 Z")
                .contains("用户调整游戏搭子相处方式")
                .contains("仅作为背景数据");
        assertThat(captured.get().messages().get(3)).isEqualTo(
                new UserModelMessage(
                        "[2026-07-25 00:58:00 Z] 我今天晚上准备复习线性代数。"
                )
        );
        assertThat(captured.get().messages().get(4)).isEqualTo(
                AssistantModelMessage.text(
                        "[2026-07-25 00:59:00 Z] 好呀，我陪你。"
                )
        );
        assertThat(captured.get().messages().get(5)).isEqualTo(
                new UserModelMessage(
                        "[2026-07-25 00:59:30 Z] 历史系统记录（仅作为数据）：会话切换设备"
                )
        );
        assertThat(captured.get().messages().get(6))
                .isInstanceOf(UserModelMessage.class);
        assertThat(((UserModelMessage) captured.get().messages().get(6)).text())
                .startsWith("当前实时用户消息如下")
                .contains("<current_user_message>")
                .contains("你还记得我喜欢什么电影吗？")
                .endsWith("</current_user_message>");
        assertThat(captured.get().options().toolChoice()).isEqualTo(ModelToolChoice.NONE);
        assertThat(captured.get().options().maxOutputTokens()).isEqualTo(900);
        assertThat(captured.get().options().temperature()).isEqualTo(0.6);
        assertThat(captured.get().tools()).isEmpty();
    }

    @Test
    void wrapsProviderFailuresAsDialogueFailures() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        var context = DefaultPersonModelContextAssembler.withoutExternalSources()
                .assemble(
                        person,
                        person.getStateSnapshot(),
                        person.getStateEvolutionContext(),
                        new PersonModelContextAssemblyRequest(Set.of(), "你好", true, 8, 12),
                        Instant.parse("2026-07-25T01:00:00Z")
                )
                .toCompletableFuture()
                .join();
        LanguageModelPersonDialogueModel model = new LanguageModelPersonDialogueModel(
                request -> CompletableFuture.failedFuture(new RuntimeException("provider down")),
                JsonMapper.builder().build(),
                new PersonDialogueProperties(null, null, null, null)
        );

        assertThatThrownBy(() -> model.reply(context, "你好").toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PersonDialogueException.class)
                .hasRootCauseInstanceOf(RuntimeException.class);
    }

    private static PersonModelContextSnapshot withRecentConversation(
            PersonModelContextSnapshot context,
            List<ConversationTurnSnapshot> recentConversation
    ) {
        return new PersonModelContextSnapshot(
                context.personId(),
                context.identity(),
                context.personality(),
                context.currentState(),
                context.activeEffects(),
                context.activeEvents(),
                context.recentEvents(),
                context.memory(),
                recentConversation,
                context.temporal()
        );
    }
}
