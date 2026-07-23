package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelMessage;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import com.laishengkai.digitalperson.infrastructure.diagnostics.DiagnosticsProperties;
import com.laishengkai.digitalperson.infrastructure.state.StateTransitionEvaluationDiagnostic;
import com.laishengkai.digitalperson.state.StateTransition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Protected diagnostics for controlled paired state-evaluation scenarios. */
@RestController
@RequestMapping("/internal/state/evaluation-contrasts")
@ConditionalOnExpression(
        "'${digital-person.llm.enabled:false}' == 'true' && "
                + "'${digital-person.diagnostics.enabled:false}' == 'true'"
)
public final class StateEvaluationContrastController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final StateTransitionEvaluationDiagnostic diagnostic;
    private final byte[] expectedToken;

    public StateEvaluationContrastController(
            StateTransitionEvaluationDiagnostic diagnostic,
            DiagnosticsProperties properties
    ) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic cannot be null");
        this.expectedToken = Objects.requireNonNull(properties, "properties cannot be null")
                .requiredToken()
                .getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/groups")
    public ResponseEntity<?> listGroups(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false)
            String suppliedToken
    ) {
        if (!matchesExpectedToken(suppliedToken)) {
            return unauthorized();
        }

        List<GroupResponse> groups = StateEvaluationContrastScenarios.groups().stream()
                .map(group -> new GroupResponse(
                        group.id(),
                        group.title(),
                        group.hypothesis(),
                        group.scenarioIds()
                ))
                .toList();
        return ResponseEntity.ok(new GroupCatalogResponse(groups));
    }

    @PostMapping("/scenarios/{scenarioId}")
    public CompletionStage<ResponseEntity<?>> evaluateScenario(
            @PathVariable String scenarioId,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false)
            String suppliedToken
    ) {
        if (!matchesExpectedToken(suppliedToken)) {
            return CompletableFuture.completedFuture(unauthorized());
        }

        return StateEvaluationContrastScenarios.find(scenarioId)
                .<CompletionStage<ResponseEntity<?>>>map(scenario ->
                        diagnostic.evaluate(scenario.context())
                                .thenApply(result -> response(scenario, result)))
                .orElseGet(() -> CompletableFuture.completedFuture(
                        ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(new ErrorResponse(
                                        "UNKNOWN_SCENARIO",
                                        "Unknown contrast scenario id: " + scenarioId
                                ))
                ));
    }

    private static ResponseEntity<?> response(
            StateEvaluationContrastScenarios.ContrastScenario scenario,
            StateTransitionEvaluationDiagnostic.Result result
    ) {
        String status = result.successful() ? "UP" : "DOWN";
        HttpStatus httpStatus = result.successful()
                ? HttpStatus.OK
                : HttpStatus.BAD_GATEWAY;

        return ResponseEntity.status(httpStatus).body(new ContrastResponse(
                status,
                new ScenarioResponse(
                        scenario.id(),
                        scenario.groupId(),
                        scenario.variant(),
                        scenario.controlledDifference()
                ),
                requestResponse(result.request()),
                rawResponse(result.response()),
                result.transitions().stream()
                        .map(StateEvaluationContrastController::transitionResponse)
                        .toList(),
                result.errorType(),
                result.errorMessage(),
                result.rootCauseType(),
                result.rootCauseMessage()
        ));
    }

    private static RequestResponse requestResponse(LanguageModelRequest request) {
        if (request == null) {
            return null;
        }

        String systemPrompt = "";
        String userPrompt = "";
        for (ModelMessage message : request.messages()) {
            if (message instanceof SystemModelMessage systemMessage) {
                systemPrompt = systemMessage.text();
            } else if (message instanceof UserModelMessage userMessage) {
                userPrompt = userMessage.text();
            }
        }

        return new RequestResponse(
                systemPrompt,
                userPrompt,
                request.options().temperature(),
                request.options().maxOutputTokens(),
                request.options().toolChoice().name()
        );
    }

    private static RawResponse rawResponse(LanguageModelResponse response) {
        if (response == null) {
            return null;
        }

        return new RawResponse(
                response.text(),
                response.finishReason().name(),
                response.usage().inputTokens(),
                response.usage().outputTokens(),
                response.usage().totalTokens(),
                response.toolCalls().stream()
                        .map(StateEvaluationContrastController::toolCallResponse)
                        .toList()
        );
    }

    private static ToolCallResponse toolCallResponse(ModelToolCall call) {
        return new ToolCallResponse(call.id(), call.name(), call.argumentsJson());
    }

    private static TransitionResponse transitionResponse(StateTransition transition) {
        return new TransitionResponse(
                transition.dimension().name(),
                transition.shape()
        );
    }

    private boolean matchesExpectedToken(String suppliedToken) {
        return suppliedToken != null && MessageDigest.isEqual(
                expectedToken,
                suppliedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ResponseEntity<ErrorResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", "Invalid internal token"));
    }

    public record GroupCatalogResponse(List<GroupResponse> groups) {
    }

    public record GroupResponse(
            String id,
            String title,
            String hypothesis,
            List<String> scenarioIds
    ) {
    }

    public record ContrastResponse(
            String status,
            ScenarioResponse scenario,
            RequestResponse request,
            RawResponse rawResponse,
            List<TransitionResponse> parsedTransitions,
            String errorType,
            String errorMessage,
            String rootCauseType,
            String rootCauseMessage
    ) {
    }

    public record ScenarioResponse(
            String id,
            String groupId,
            String variant,
            String controlledDifference
    ) {
    }

    public record RequestResponse(
            String systemPrompt,
            String userPrompt,
            Double temperature,
            Integer maxOutputTokens,
            String toolChoice
    ) {
    }

    public record RawResponse(
            String assistantText,
            String finishReason,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            List<ToolCallResponse> toolCalls
    ) {
    }

    public record ToolCallResponse(
            String id,
            String name,
            String argumentsJson
    ) {
    }

    public record TransitionResponse(String dimension, double shape) {
    }

    public record ErrorResponse(String status, String message) {
    }
}
