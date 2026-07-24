package com.laishengkai.digitalperson.infrastructure.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelMessage;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectDirection;
import com.laishengkai.digitalperson.state.StateEffectDraft;
import com.laishengkai.digitalperson.state.StateEffectEndPolicy;
import com.laishengkai.digitalperson.state.StateEffectIntensity;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateTransition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Single source of truth for the model-facing state-effect protocol. */
final class StateEffectProtocol {
    static final String TOOL_NAME = "submit_state_effects";
    static final int MAX_OUTPUT_TOKENS = 4_096;
    static final int MAX_EFFECT_DURATION_MINUTES = 43_200;
    static final int MAX_EFFECTS = 16;
    static final int MAX_CAUSE_LENGTH = 500;

    private static final int MAX_CORRECTION_REASON_LENGTH = 300;
    private static final int MAX_INSTANT_DURATION_MINUTES = 10;
    private static final int MAX_EXTREME_DURATION_MINUTES = 60;
    private static final int MAX_HIGH_DURATION_MINUTES = 180;
    private static final List<StateEffectType> MODEL_EFFECT_TYPES = List.of(
            StateEffectType.EMOTIONAL,
            StateEffectType.COGNITIVE,
            StateEffectType.PHYSICAL,
            StateEffectType.SOCIAL
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    private static final ModelToolSpecification SUBMISSION_TOOL =
            new ModelToolSpecification(
                    TOOL_NAME,
                    "提交 newEvent 直接产生的独立短期状态效果。必须且只能调用一次；"
                            + "没有显著效果时提交空 effects 数组。",
                    buildStrictToolSchema()
            );

    private StateEffectProtocol() {
    }

    static ModelToolSpecification submissionTool() {
        return SUBMISSION_TOOL;
    }

    static EventStateImpact parseResponse(LanguageModelResponse response) {
        return parseResponse(response, "protocol-validation");
    }

    static EventStateImpact parseResponse(
            LanguageModelResponse response,
            String stableSeed
    ) {
        LanguageModelResponse safeResponse = Objects.requireNonNull(
                response,
                "languageModelGateway response cannot be null"
        );
        String seed = requireText(stableSeed, "stableSeed");
        List<ModelToolCall> toolCalls = safeResponse.toolCalls();
        if (toolCalls.size() != 1) {
            throw new StateTransitionEvaluationException(
                    "model must call " + TOOL_NAME + " exactly once; received "
                            + toolCalls.size() + " tool calls"
            );
        }
        ModelToolCall toolCall = toolCalls.getFirst();
        if (!TOOL_NAME.equals(toolCall.name())) {
            throw new StateTransitionEvaluationException(
                    "model called unexpected result-submission tool: "
                            + toolCall.name()
            );
        }
        return parseSubmission(toolCall.argumentsJson(), seed);
    }

    static LanguageModelRequest correctionRequest(
            LanguageModelRequest request,
            StateTransitionEvaluationException invalidSubmission
    ) {
        List<ModelMessage> messages = new ArrayList<>(Objects.requireNonNull(
                request,
                "request cannot be null"
        ).messages());
        String correction = "\n\n上一条 submit_state_effects 工具参数未通过 Java 语义校验："
                + safeReason(invalidSubmission)
                + "。请重新提交一次完整工具调用。必须严格遵守当前工具 Schema；"
                + "尤其不得把某一类型不支持的维度放进该 effect，跨类型影响必须拆成多个 effect，"
                + "并正确选择 direction 与 intensity。";
        if (!messages.isEmpty() && messages.getFirst() instanceof SystemModelMessage system) {
            messages.set(0, new SystemModelMessage(system.text() + correction));
        } else {
            messages.addFirst(new SystemModelMessage(correction.strip()));
        }
        return new LanguageModelRequest(messages, request.options(), request.tools());
    }

    private static EventStateImpact parseSubmission(
            String argumentsJson,
            String stableSeed
    ) {
        final EffectSubmission submission;
        try {
            submission = OBJECT_MAPPER.readValue(
                    argumentsJson,
                    EffectSubmission.class
            );
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new StateTransitionEvaluationException(
                    "model returned invalid state-effect tool arguments",
                    error
            );
        }
        if (submission == null || submission.effects() == null) {
            throw new StateTransitionEvaluationException(
                    "state-effect submission must contain effects"
            );
        }
        if (submission.effects().size() > MAX_EFFECTS) {
            throw new StateTransitionEvaluationException(
                    "state-effect submission exceeds maximum effect count " + MAX_EFFECTS
            );
        }

        List<StateEffectDraft> drafts = new ArrayList<>();
        for (int index = 0; index < submission.effects().size(); index++) {
            drafts.add(toEffectDraft(
                    submission.effects().get(index),
                    stableSeed,
                    index
            ));
        }
        return new EventStateImpact(drafts);
    }

    private static StateEffectDraft toEffectDraft(
            EffectItem item,
            String stableSeed,
            int effectIndex
    ) {
        if (item == null) {
            throw new StateTransitionEvaluationException("effects cannot contain null items");
        }
        StateEffectType type = parseType(item.type());
        String cause = requireText(item.cause(), "cause");
        if (cause.length() > MAX_CAUSE_LENGTH) {
            throw new StateTransitionEvaluationException(
                    "cause exceeds maximum length " + MAX_CAUSE_LENGTH
            );
        }
        StateEffectEndPolicy endPolicy = parseEndPolicy(item.endPolicy());
        if (item.durationMinutes() == null) {
            throw new StateTransitionEvaluationException("durationMinutes is required");
        }
        int durationMinutes = item.durationMinutes();
        if (durationMinutes < 0 || durationMinutes > MAX_EFFECT_DURATION_MINUTES) {
            throw new StateTransitionEvaluationException(
                    "durationMinutes must be between 0 and "
                            + MAX_EFFECT_DURATION_MINUTES
            );
        }
        if (endPolicy == StateEffectEndPolicy.EVENT_END && durationMinutes != 0) {
            throw new StateTransitionEvaluationException(
                    "EVENT_END effects require durationMinutes=0"
            );
        }
        if (endPolicy != StateEffectEndPolicy.EVENT_END && durationMinutes == 0) {
            throw new StateTransitionEvaluationException(
                    "fixed-time effects require positive durationMinutes"
            );
        }
        if (item.transitions() == null || item.transitions().isEmpty()) {
            throw new StateTransitionEvaluationException(
                    "every effect requires at least one transition"
            );
        }
        List<StateTransition> transitions = parseTransitions(
                type,
                endPolicy,
                durationMinutes,
                item.transitions(),
                stableSeed,
                effectIndex
        );
        try {
            return new StateEffectDraft(
                    type,
                    cause,
                    transitions,
                    endPolicy,
                    Duration.ofMinutes(durationMinutes)
            );
        } catch (IllegalArgumentException error) {
            throw new StateTransitionEvaluationException(
                    "invalid state effect",
                    error
            );
        }
    }

    private static List<StateTransition> parseTransitions(
            StateEffectType type,
            StateEffectEndPolicy endPolicy,
            int durationMinutes,
            List<TransitionItem> items,
            String stableSeed,
            int effectIndex
    ) {
        Set<StateDimension> seenDimensions = EnumSet.noneOf(StateDimension.class);
        List<StateTransition> transitions = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            transitions.add(toTransition(
                    type,
                    endPolicy,
                    durationMinutes,
                    items.get(index),
                    seenDimensions,
                    stableSeed,
                    effectIndex,
                    index
            ));
        }
        return List.copyOf(transitions);
    }

    private static StateTransition toTransition(
            StateEffectType type,
            StateEffectEndPolicy endPolicy,
            int durationMinutes,
            TransitionItem item,
            Set<StateDimension> seenDimensions,
            String stableSeed,
            int effectIndex,
            int transitionIndex
    ) {
        if (item == null) {
            throw new StateTransitionEvaluationException(
                    "transitions cannot contain null items"
            );
        }
        String dimensionName = requireText(item.dimension(), "dimension");
        final StateDimension dimension;
        try {
            dimension = StateDimension.valueOf(dimensionName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new StateTransitionEvaluationException(
                    "unknown state dimension: " + dimensionName,
                    error
            );
        }
        if (!seenDimensions.add(dimension)) {
            throw new StateTransitionEvaluationException(
                    "duplicate state dimension in one effect: " + dimension
            );
        }
        if (!type.supports(dimension)) {
            throw new StateTransitionEvaluationException(
                    "effect type " + type + " does not support " + dimension
            );
        }

        StateEffectDirection direction = parseDirection(item.direction());
        StateEffectIntensity intensity = parseIntensity(item.intensity());
        validateIntensity(intensity, endPolicy, durationMinutes);
        String transitionSeed = stableSeed
                + '|' + effectIndex
                + '|' + transitionIndex
                + '|' + dimension.name()
                + '|' + direction.name()
                + '|' + intensity.name();
        try {
            return new StateTransition(
                    dimension,
                    intensity.resolve(direction, transitionSeed)
            );
        } catch (IllegalArgumentException error) {
            throw new StateTransitionEvaluationException(
                    "invalid intensity for dimension: " + dimension,
                    error
            );
        }
    }

    private static void validateIntensity(
            StateEffectIntensity intensity,
            StateEffectEndPolicy endPolicy,
            int durationMinutes
    ) {
        if (endPolicy == StateEffectEndPolicy.EVENT_END
                && intensity.ordinal() > StateEffectIntensity.MEDIUM.ordinal()) {
            throw new StateTransitionEvaluationException(
                    "EVENT_END effects only allow LOW or MEDIUM intensity"
            );
        }
        if (intensity == StateEffectIntensity.INSTANT) {
            if (endPolicy != StateEffectEndPolicy.FIXED_TIME) {
                throw new StateTransitionEvaluationException(
                        "INSTANT intensity requires FIXED_TIME"
                );
            }
            if (durationMinutes > MAX_INSTANT_DURATION_MINUTES) {
                throw new StateTransitionEvaluationException(
                        "INSTANT intensity durationMinutes cannot exceed "
                                + MAX_INSTANT_DURATION_MINUTES
                );
            }
        }
        if (intensity == StateEffectIntensity.EXTREME
                && durationMinutes > MAX_EXTREME_DURATION_MINUTES) {
            throw new StateTransitionEvaluationException(
                    "EXTREME intensity durationMinutes cannot exceed "
                            + MAX_EXTREME_DURATION_MINUTES
            );
        }
        if (intensity == StateEffectIntensity.HIGH
                && endPolicy != StateEffectEndPolicy.EVENT_END
                && durationMinutes > MAX_HIGH_DURATION_MINUTES) {
            throw new StateTransitionEvaluationException(
                    "HIGH intensity durationMinutes cannot exceed "
                            + MAX_HIGH_DURATION_MINUTES
            );
        }
    }

    private static StateEffectType parseType(String value) {
        String normalized = requireText(value, "type").toUpperCase(Locale.ROOT);
        try {
            StateEffectType type = StateEffectType.valueOf(normalized);
            if (!MODEL_EFFECT_TYPES.contains(type)) {
                throw new IllegalArgumentException("unsupported model effect type");
            }
            return type;
        } catch (IllegalArgumentException error) {
            throw new StateTransitionEvaluationException(
                    "unknown or unsupported effect type: " + value,
                    error
            );
        }
    }

    private static StateEffectEndPolicy parseEndPolicy(String value) {
        String normalized = requireText(value, "endPolicy").toUpperCase(Locale.ROOT);
        try {
            return StateEffectEndPolicy.valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new StateTransitionEvaluationException(
                    "unknown effect end policy: " + value,
                    error
            );
        }
    }

    private static StateEffectDirection parseDirection(String value) {
        String normalized = requireText(value, "direction").toUpperCase(Locale.ROOT);
        try {
            return StateEffectDirection.valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new StateTransitionEvaluationException(
                    "unknown effect direction: " + value,
                    error
            );
        }
    }

    private static StateEffectIntensity parseIntensity(String value) {
        String normalized = requireText(value, "intensity").toUpperCase(Locale.ROOT);
        try {
            return StateEffectIntensity.valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new StateTransitionEvaluationException(
                    "unknown effect intensity: " + value,
                    error
            );
        }
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
                .map(StateEffectProtocol::effectVariantSchema)
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
                """.formatted(MAX_EFFECTS, variants).strip();
    }

    private static String effectVariantSchema(StateEffectType type) {
        String dimensions = type.supportedDimensions().stream()
                .sorted()
                .map(StateEffectProtocol::quoted)
                .collect(Collectors.joining(","));
        String intensities = java.util.Arrays.stream(StateEffectIntensity.values())
                .map(value -> "\"" + value.name() + "\"")
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
                          "direction":{"type":"string","enum":["INCREASE","DECREASE"]},
                          "intensity":{"type":"string","enum":[%s]}
                        },
                        "required":["dimension","direction","intensity"],
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
                intensities,
                MAX_EFFECT_DURATION_MINUTES
        ).strip();
    }

    private static String quoted(StateDimension dimension) {
        return "\"" + dimension.name() + "\"";
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new StateTransitionEvaluationException(
                    fieldName + " must be present and non-blank"
            );
        }
        return normalized;
    }

    private record EffectSubmission(List<EffectItem> effects) {
    }

    private record EffectItem(
            String type,
            String cause,
            List<TransitionItem> transitions,
            String endPolicy,
            Integer durationMinutes
    ) {
    }

    private record TransitionItem(
            String dimension,
            String direction,
            String intensity
    ) {
    }
}
