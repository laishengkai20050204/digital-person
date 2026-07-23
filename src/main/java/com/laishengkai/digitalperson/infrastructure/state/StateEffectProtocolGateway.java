package com.laishengkai.digitalperson.infrastructure.state;

import com.laishengkai.digitalperson.dialogue.LanguageModelGateway;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelMessage;
import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateTransition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Narrows the state-effect tool schema by semantic effect type and retries one
 * invalid model submission with an explicit protocol correction.
 */
final class StateEffectProtocolGateway implements LanguageModelGateway {
    private static final int MAX_CAUSE_LENGTH = 500;
    private static final int MAX_CORRECTION_REASON_LENGTH = 300;
    private static final List<StateEffectType> MODEL_EFFECT_TYPES = List.of(
            StateEffectType.EMOTIONAL,
            StateEffectType.COGNITIVE,
            StateEffectType.PHYSICAL,
            StateEffectType.SOCIAL
    );
    private static final String STRICT_TOOL_SCHEMA = buildStrictToolSchema();

    private final LanguageModelGateway delegate;

    StateEffectProtocolGateway(LanguageModelGateway delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    }

    @Override
    public CompletionStage<LanguageModelResponse> invoke(LanguageModelRequest request) {
        LanguageModelRequest hardenedRequest = hardenRequest(Objects.requireNonNull(
                request,
                "request cannot be null"
        ));
        return invokeValidated(hardenedRequest, true);
    }

    private CompletionStage<LanguageModelResponse> invokeValidated(
            LanguageModelRequest request,
            boolean retryAllowed
    ) {
        CompletionStage<LanguageModelResponse> invocation = Objects.requireNonNull(
                delegate.invoke(request),
                "delegate stage cannot be null"
        );
        return invocation.thenCompose(response -> {
            try {
                LanguageModelStateTransitionEvaluator.parseResponse(response);
                return CompletableFuture.completedFuture(response);
            } catch (StateTransitionEvaluationException invalidSubmission) {
                if (!retryAllowed) {
                    return CompletableFuture.failedFuture(invalidSubmission);
                }
                return invokeValidated(
                        correctionRequest(request, invalidSubmission),
                        false
                );
            }
        });
    }

    private static LanguageModelRequest hardenRequest(LanguageModelRequest request) {
        List<ModelToolSpecification> tools = request.tools().stream()
                .map(tool -> LanguageModelStateTransitionEvaluator.TOOL_NAME.equals(tool.name())
                        ? new ModelToolSpecification(
                                tool.name(),
                                tool.description(),
                                STRICT_TOOL_SCHEMA
                        )
                        : tool)
                .toList();
        return new LanguageModelRequest(request.messages(), request.options(), tools);
    }

    private static LanguageModelRequest correctionRequest(
            LanguageModelRequest request,
            StateTransitionEvaluationException invalidSubmission
    ) {
        List<ModelMessage> messages = new ArrayList<>(request.messages());
        String correction = "\n\n上一条 submit_state_effects 工具参数未通过 Java 语义校验："
                + safeReason(invalidSubmission)
                + "。请重新提交一次完整工具调用。必须严格遵守当前工具 Schema；"
                + "尤其不得把某一类型不支持的维度放进该 effect，跨类型影响必须拆成多个 effect。";
        if (!messages.isEmpty() && messages.getFirst() instanceof SystemModelMessage system) {
            messages.set(0, new SystemModelMessage(system.text() + correction));
        } else {
            messages.addFirst(new SystemModelMessage(correction.strip()));
        }
        return new LanguageModelRequest(messages, request.options(), request.tools());
    }

    private static String safeReason(StateTransitionEvaluationException error) {
        String reason = Objects.requireNonNullElse(
                error.getMessage(),
                "工具参数不符合状态效果协议"
        ).replace('\n', ' ').replace('\r', ' ').strip();
        if (reason.length() <= MAX_CORRECTION_REASON_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_CORRECTION_REASON_LENGTH);
    }

    private static String buildStrictToolSchema() {
        String variants = MODEL_EFFECT_TYPES.stream()
                .map(StateEffectProtocolGateway::effectVariantSchema)
                .collect(Collectors.joining(","));
        return """
                {
                  "type":"object",
                  "properties":{
                    "effects":{
                      "type":"array",
                      "maxItems":%d,
                      "items":{"oneOf":[%s]}
                    }
                  },
                  "required":["effects"],
                  "additionalProperties":false
                }
                """.formatted(
                        LanguageModelStateTransitionEvaluator.MAX_EFFECTS,
                        variants
                ).strip();
    }

    private static String effectVariantSchema(StateEffectType type) {
        String dimensions = type.supportedDimensions().stream()
                .sorted()
                .map(StateEffectProtocolGateway::quoted)
                .collect(Collectors.joining(","));
        return """
                {
                  "type":"object",
                  "properties":{
                    "type":{"type":"string","enum":["%s"]},
                    "cause":{"type":"string","minLength":1,"maxLength":%d},
                    "transitions":{
                      "type":"array",
                      "minItems":1,
                      "maxItems":%d,
                      "items":{
                        "type":"object",
                        "properties":{
                          "dimension":{"type":"string","enum":[%s]},
                          "shape":{"type":"number","minimum":%s,"maximum":%s,"description":"每小时带符号的有限非零指数变化速率；不得为 0"}
                        },
                        "required":["dimension","shape"],
                        "additionalProperties":false
                      }
                    },
                    "endPolicy":{"type":"string","enum":["EVENT_END","FIXED_TIME","EVENT_END_OR_FIXED_TIME"]},
                    "durationMinutes":{"type":"integer","minimum":0,"maximum":%d}
                  },
                  "required":["type","cause","transitions","endPolicy","durationMinutes"],
                  "additionalProperties":false
                }
                """.formatted(
                        type.name(),
                        MAX_CAUSE_LENGTH,
                        type.supportedDimensions().size(),
                        dimensions,
                        -StateTransition.MAX_ABSOLUTE_SHAPE,
                        StateTransition.MAX_ABSOLUTE_SHAPE,
                        LanguageModelStateTransitionEvaluator.MAX_EFFECT_DURATION_MINUTES
                ).strip();
    }

    private static String quoted(StateDimension dimension) {
        return "\"" + dimension.name() + "\"";
    }
}
