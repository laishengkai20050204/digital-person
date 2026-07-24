package com.laishengkai.digitalperson.infrastructure.dialogue;

import com.laishengkai.digitalperson.application.DefaultPersonModelContextAssembler;
import com.laishengkai.digitalperson.application.PersonModelContextAssemblyRequest;
import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.PersonDialogueException;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.personality.Personality;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageModelPersonDialogueModelTest {

    @Test
    void sendsAssembledContextAndReturnsDirectTextReply() {
        Person person = Person.create(new Personality(0.7, 0.6, 0.5, 0.8, 0.7, 0.9));
        var context = DefaultPersonModelContextAssembler.withoutExternalSources()
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
        assertThat(captured.get().messages()).hasSize(2);
        assertThat(captured.get().messages().getFirst())
                .isInstanceOf(SystemModelMessage.class);
        assertThat(((SystemModelMessage) captured.get().messages().getFirst()).text())
                .contains("context_json")
                .contains(person.getId().toString());
        assertThat(captured.get().messages().get(1))
                .isEqualTo(new UserModelMessage("你还记得我喜欢什么电影吗？"));
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
}
