package com.laishengkai.digitalperson.infrastructure.dialogue;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelResponseFormat;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import com.laishengkai.digitalperson.infrastructure.memory.StructuredMemoryExtractionProperties;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.StructuredMemoryExtraction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageModelStructuredMemoryExtractionModelTest {

    @Test
    void extractsConstrainedEntitiesAndFactsWithLocalTimestamps() {
        AtomicReference<LanguageModelRequest> captured = new AtomicReference<>();
        LanguageModelStructuredMemoryExtractionModel model = model(captured, """
                {
                  "entities": [
                    {
                      "reference": "e1",
                      "entityType": "PERSON",
                      "canonicalName": "林晓雨",
                      "aliases": ["小林"],
                      "description": "用户的游戏搭子",
                      "confidence": 0.94
                    }
                  ],
                  "facts": [
                    {
                      "section": "RELATIONSHIP",
                      "domain": "SOCIAL",
                      "subjectReference": "e1",
                      "predicate": "RELATION_TO_USER",
                      "objectReference": "",
                      "textValue": "游戏搭子",
                      "statement": "林晓雨是用户的游戏搭子",
                      "confidence": 0.92,
                      "importance": 0.76,
                      "validFrom": null,
                      "validUntil": null,
                      "conflictMode": "KEEP_EXISTING"
                    }
                  ]
                }
                """);

        StructuredMemoryExtraction extraction = model.extract(
                List.of(new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "我最近认识了林晓雨，经常一起打王者。",
                        Instant.parse("2026-07-27T00:00:00Z")
                )),
                ZoneId.of("Asia/Shanghai")
        ).toCompletableFuture().join();

        assertThat(extraction.entities()).hasSize(1);
        assertThat(extraction.entities().getFirst().aliases()).containsExactly("小林");
        assertThat(extraction.facts()).hasSize(1);
        assertThat(extraction.facts().getFirst().section())
                .isEqualTo(MemorySection.RELATIONSHIP);
        assertThat(((UserModelMessage) captured.get().messages().get(1)).text())
                .contains("2026-07-27 08:00:00 +08:00 Asia/Shanghai")
                .contains("林晓雨");
        assertThat(captured.get().options().temperature()).isEqualTo(0.1);
        assertThat(captured.get().options().maxOutputTokens()).isEqualTo(1200);
        assertThat(captured.get().options().toolChoice()).isEqualTo(ModelToolChoice.NONE);
        assertThat(captured.get().options().responseFormat().type())
                .isEqualTo(ModelResponseFormat.Type.JSON_OBJECT);
    }

    @Test
    void rejectsWorkingMemoryWithoutAnExpiryAndCredentialContent() {
        LanguageModelStructuredMemoryExtractionModel model = model(
                new AtomicReference<>(),
                """
                {
                  "entities": [],
                  "facts": [
                    {
                      "section": "WORKING_MEMORY",
                      "domain": "PROJECT",
                      "subjectReference": "",
                      "predicate": "CURRENT_TASK",
                      "objectReference": "",
                      "textValue": "临时排障",
                      "statement": "用户当前正在临时排障",
                      "confidence": 0.9,
                      "importance": 0.7,
                      "validFrom": null,
                      "validUntil": null,
                      "conflictMode": "SUPERSEDE_EXISTING"
                    },
                    {
                      "section": "USER_PROFILE",
                      "domain": "SECURITY",
                      "subjectReference": "",
                      "predicate": "API_KEY",
                      "objectReference": "",
                      "textValue": "sk-abcdefghijklmnop",
                      "statement": "用户的密钥是 sk-abcdefghijklmnop",
                      "confidence": 0.99,
                      "importance": 0.99,
                      "validFrom": null,
                      "validUntil": null,
                      "conflictMode": "KEEP_EXISTING"
                    }
                  ]
                }
                """
        );

        StructuredMemoryExtraction extraction = model.extract(
                List.of(new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "测试",
                        Instant.EPOCH
                )),
                ZoneId.of("UTC")
        ).toCompletableFuture().join();

        assertThat(extraction.facts()).isEmpty();
    }

    private static LanguageModelStructuredMemoryExtractionModel model(
            AtomicReference<LanguageModelRequest> captured,
            String response
    ) {
        return new LanguageModelStructuredMemoryExtractionModel(
                request -> {
                    captured.set(request);
                    return CompletableFuture.completedFuture(
                            LanguageModelResponse.text(response)
                    );
                },
                JsonMapper.builder().build(),
                new StructuredMemoryExtractionProperties(
                        true,
                        2,
                        8,
                        8,
                        12,
                        0.70,
                        0.35,
                        1200,
                        0.1
                )
        );
    }
}
