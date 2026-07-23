package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.LanguageModelResponse;
import com.laishengkai.digitalperson.dialogue.ModelMessage;
import com.laishengkai.digitalperson.dialogue.ModelToolCall;
import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;
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

/** Protected diagnostics that expose exact synthetic prompts and raw model output. */
@RestController
@RequestMapping("/internal/state/evaluation-diagnostics")
@ConditionalOnExpression(
        "'${digital-person.llm.enabled:false}' == 'true' && "
                + "'${digital-person.diagnostics.enabled:false}' == 'true'"
)
public final class StateEvaluationDiagnosticController {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final StateTransitionEvaluationDiagnostic diagnostic;
    private final byte[] expectedToken;

    public StateEvaluationDiagnosticController(
            StateTransitionEvaluationDiagnostic diagnostic,
            DiagnosticsProperties properties
    ) {
        this.diagnostic = Objects.requireNonNull(
                diagnostic,
                "diagnostic cannot be null"
        );
        this.expectedToken = Objects.requireNonNull(properties, "properties cannot be null")
                .requiredToken()
                .getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/scenarios")
    public ResponseEntity<?> listScenarios(
            @RequestHeader(
                    name = INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken
    ) {
        if (!matchesExpectedToken(suppliedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("UNAUTHORIZED", "Invalid internal token"));
        }

        List<ScenarioSummaryResponse> scenarios =
                StateEvaluationDiagnosticScenarios.all().stream()
                        .map(StateEvaluationDiagnosticController::scenarioSummary)
                        .toList();
        return ResponseEntity.ok(new ScenarioCatalogResponse(scenarios));
    }

    @PostMapping("/scenarios/{scenarioId}")
    public CompletionStage<ResponseEntity<?>> evaluateScenario(
            @PathVariable String scenarioId,
            @RequestHeader(
                    name = INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken
    ) {
        if (!matchesExpectedToken(suppliedToken)) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(new ErrorResponse(
                                    "UNAUTHORIZED",
                                    "Invalid internal token"
                            ))
            );
        }

        return StateEvaluationDiagnosticScenarios.find(scenarioId)
                .<CompletionStage<ResponseEntity<?>>>map(scenario ->
                        diagnostic.evaluate(scenario.context())
                                .thenApply(result -> diagnosticResponse(scenario, result)))
                .orElseGet(() -> CompletableFuture.completedFuture(
                        ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(new ErrorResponse(
                                        "UNKNOWN_SCENARIO",
                                        "Unknown scenario id: " + scenarioId
                                ))
                ));
    }

    private static ResponseEntity<?> diagnosticResponse(
            StateEvaluationDiagnosticScenarios.Scenario scenario,
            StateTransitionEvaluationDiagnostic.Result result
    ) {
        RequestResponse request = requestResponse(result.request());
        RawModelResponse rawResponse = rawResponse(result.response());
        List<TransitionResponse> transitions = result.transitions().stream()
                .map(transition -> new TransitionResponse(
                        transition.dimension().name(),
                        transition.shape()
                ))
                .toList();
        ExpectationResponse expectation = expectationResponse(
                scenario,
                result.transitions()
        );

        String status;
        HttpStatus httpStatus;
        if (!result.successful()) {
            status = "DOWN";
            httpStatus = HttpStatus.BAD_GATEWAY;
        } else if (!expectation.passed()) {
            status = "EXPECTATION_MISMATCH";
            httpStatus = HttpStatus.OK;
        } else {
            status = "UP";
            httpStatus = HttpStatus.OK;
        }

        return ResponseEntity.status(httpStatus).body(new DiagnosticResponse(
                status,
                scenarioSummary(scenario),
                request,
                rawResponse,
                transitions,
                expectation,
                normalize(result.errorType()),
                normalize(result.errorMessage())
        ));
    }

    private static ScenarioSummaryResponse scenarioSummary(
            StateEvaluationDiagnosticScenarios.Scenario scenario
    ) {
        List<ExpectedTransitionResponse> expectations = scenario.expectations().stream()
                .map(expectation -> new ExpectedTransitionResponse(
                        expectation.dimension().name(),
                        expectation.direction().name()
                ))
                .toList();
        return new ScenarioSummaryResponse(
                scenario.id(),
                scenario.title(),
                scenario.description(),
                scenario.expectsNoMaterialEffect(),
                expectations
        );
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

        List<ToolSpecificationResponse> tools = request.tools().stream()
                .map(StateEvaluationDiagnosticController::toolSpecificationResponse)
                .toList();

        return new RequestResponse(
                systemPrompt,
                userPrompt,
                request.options().temperature(),
                request.options().maxOutputTokens(),
                request.options().toolChoice().name(),
                tools
        );
    }

    private static ToolSpecificationResponse toolSpecificationResponse(
            ModelToolSpecification tool
    ) {
        return new ToolSpecificationResponse(
                tool.name(),
                tool.description(),
                tool.parametersJsonSchema()
        );
    }

    private static RawModelResponse rawResponse(LanguageModelResponse response) {
        if (response == null) {
            return null;
        }

        List<ToolCallResponse> toolCalls = response.toolCalls().stream()
                .map(StateEvaluationDiagnosticController::toolCallResponse)
                .toList();

        return new RawModelResponse(
                response.text(),
                response.finishReason().name(),
                new UsageResponse(
                        response.usage().inputTokens(),
                        response.usage().outputTokens(),
                        response.usage().totalTokens()
                ),
                toolCalls
        );
    }

    private static ToolCallResponse toolCallResponse(ModelToolCall toolCall) {
        return new ToolCallResponse(
                toolCall.id(),
                toolCall.name(),
                toolCall.argumentsJson()
        );
    }

    private static ExpectationResponse expectationResponse(
            StateEvaluationDiagnosticScenarios.Scenario scenario,
            List<StateTransition> transitions
    ) {
        if (scenario.expectsNoMaterialEffect()) {
            return new ExpectationResponse(
                    transitions.isEmpty(),
                    true,
                    transitions.size(),
                    List.of()
            );
        }

        List<ExpectationCheckResponse> checks = scenario.expectations().stream()
                .map(expectation -> expectationCheck(expectation, transitions))
                .toList();
        boolean passed = checks.stream().allMatch(ExpectationCheckResponse::matched);
        return new ExpectationResponse(
                passed,
                false,
                transitions.size(),
                checks
        );
    }

    private static ExpectationCheckResponse expectationCheck(
            StateEvaluationDiagnosticScenarios.ExpectedTransition expectation,
            List<StateTransition> transitions
    ) {
        StateTransition observed = transitions.stream()
                .filter(transition -> transition.dimension() == expectation.dimension())
                .findFirst()
                .orElse(null);
        Double observedShape = observed == null ? null : observed.shape();
        boolean matched = observed != null && switch (expectation.direction()) {
            case INCREASE -> observed.shape() > 0.0;
            case DECREASE -> observed.shape() < 0.0;
        };

        return new ExpectationCheckResponse(
                expectation.dimension().name(),
                expectation.direction().name(),
                observedShape,
                matched
        );
    }

    private boolean matchesExpectedToken(String suppliedToken) {
        if (suppliedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken,
                suppliedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    public record ScenarioCatalogResponse(List<ScenarioSummaryResponse> scenarios) {
    }

    public record ScenarioSummaryResponse(
            String id,
            String title,
            String description,
            boolean expectsNoMaterialEffect,
            List<ExpectedTransitionResponse> expectedTransitions
    ) {
    }

    public record ExpectedTransitionResponse(String dimension, String direction) {
    }

    public record DiagnosticResponse(
            String status,
            ScenarioSummaryResponse scenario,
            RequestResponse request,
            RawModelResponse rawResponse,
            List<TransitionResponse> parsedTransitions,
            ExpectationResponse expectation,
            String errorType,
            String errorMessage
    ) {
    }

    public record RequestResponse(
            String systemPrompt,
            String userPrompt,
            Double temperature,
            Integer maxOutputTokens,
            String toolChoice,
            List<ToolSpecificationResponse> tools
    ) {
    }

    public record ToolSpecificationResponse(
            String name,
            String description,
            String parametersJsonSchema
    ) {
    }

    public record RawModelResponse(
            String assistantText,
            String finishReason,
            UsageResponse usage,
            List<ToolCallResponse> toolCalls
    ) {
    }

    public record UsageResponse(
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens
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

    public record ExpectationResponse(
            boolean passed,
            boolean expectsNoMaterialEffect,
            int observedTransitionCount,
            List<ExpectationCheckResponse> checks
    ) {
    }

    public record ExpectationCheckResponse(
            String dimension,
            String direction,
            Double observedShape,
            boolean matched
    ) {
    }

    public record ErrorResponse(String status, String message) {
    }
}
