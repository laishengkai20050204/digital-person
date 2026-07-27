package com.laishengkai.digitalperson.infrastructure.dialogue;

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
import com.laishengkai.digitalperson.infrastructure.memory.StructuredMemoryExtractionProperties;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.StructuredMemoryExtraction;
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

class LanguageModelStructuredMemoryExtractionModelTest {

    @Test
    void extractsConstrainedEntitiesAndFactsWithRequiredSubmissionTool() {
        SequenceGateway gateway = new SequenceGateway(toolResponse(validSubmission()));
        LanguageModelStructuredMemoryExtractionModel model = model(gateway, 1200);

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

        LanguageModelRequest request = gateway.requests().getFirst();
        assertThat(((UserModelMessage) request.messages().get(1)).text())
                .contains("2026-07-27 08:00:00 +08:00 Asia/Shanghai")
                .contains("林晓雨");
        assertThat(request.options().temperature()).isEqualTo(0.1);
        assertThat(request.options().maxOutputTokens()).isEqualTo(1200);
        assertThat(request.options().toolChoice()).isEqualTo(ModelToolChoice.REQUIRED);
        assertThat(request.options().responseFormat().type())
                .isEqualTo(ModelResponseFormat.Type.TEXT);
        assertThat(request.tools()).singleElement()
                .satisfies(tool -> {
                    assertThat(tool.name()).isEqualTo(
                            LanguageModelStructuredMemoryExtractionModel.TOOL_NAME
                    );
                    assertThat(tool.parametersJsonSchema())
                            .contains("\"required\":[\"entities\",\"facts\"]")
                            .contains("\"maxItems\":8")
                            .contains("\"maxItems\":12");
                });
    }

    @Test
    void retriesExactlyOnceWhenFirstSubmissionIsMissingRequiredArrays() {
        SequenceGateway gateway = new SequenceGateway(
                toolResponse("{\"facts\":[]}"),
                toolResponse("{\"entities\":[],\"facts\":[]}")
        );
        LanguageModelStructuredMemoryExtractionModel model = model(gateway, 4096);

        StructuredMemoryExtraction extraction = model.extract(
                List.of(new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "只是普通寒暄。",
                        Instant.EPOCH
                )),
                ZoneId.of("UTC")
        ).toCompletableFuture().join();

        assertThat(extraction.entities()).isEmpty();
        assertThat(extraction.facts()).isEmpty();
        assertThat(gateway.requests()).hasSize(2);
        SystemModelMessage retrySystem = (SystemModelMessage) gateway.requests()
                .get(1)
                .messages()
                .getFirst();
        assertThat(retrySystem.text())
                .contains("未通过 Java 校验")
                .contains("必须同时包含 entities 和 facts 数组")
                .contains("不得输出普通文字");
    }

    @Test
    void retriesExactlyOnceWhenFirstResponseDoesNotCallSubmissionTool() {
        SequenceGateway gateway = new SequenceGateway(
                LanguageModelResponse.text("{\"entities\":[],\"facts\":[]}"),
                toolResponse("{\"entities\":[],\"facts\":[]}")
        );
        LanguageModelStructuredMemoryExtractionModel model = model(gateway, 4096);

        StructuredMemoryExtraction extraction = model.extract(
                List.of(new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "普通消息。",
                        Instant.EPOCH
                )),
                ZoneId.of("UTC")
        ).toCompletableFuture().join();

        assertThat(extraction.entities()).isEmpty();
        assertThat(extraction.facts()).isEmpty();
        assertThat(gateway.requests()).hasSize(2);
    }

    @Test
    void rejectsWorkingMemoryWithoutAnExpiryAndCredentialContent() {
        SequenceGateway gateway = new SequenceGateway(toolResponse("""
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
                """));
        LanguageModelStructuredMemoryExtractionModel model = model(gateway, 4096);

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
            LanguageModelGateway gateway,
            int maxOutputTokens
    ) {
        return new LanguageModelStructuredMemoryExtractionModel(
                gateway,
                JsonMapper.builder().build(),
                new StructuredMemoryExtractionProperties(
                        true,
                        2,
                        8,
                        8,
                        12,
                        0.70,
                        0.35,
                        maxOutputTokens,
                        0.1
                )
        );
    }

    private static LanguageModelResponse toolResponse(String argumentsJson) {
        return new LanguageModelResponse(
                AssistantModelMessage.toolCalls(List.of(new ModelToolCall(
                        "call-1",
                        LanguageModelStructuredMemoryExtractionModel.TOOL_NAME,
                        argumentsJson
                ))),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unknown()
        );
    }

    private static String validSubmission() {
        return """
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
