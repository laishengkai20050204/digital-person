package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.agent.AgentExecutor;
import com.laishengkai.digitalperson.agent.AgentRequest;
import com.laishengkai.digitalperson.agent.AgentResult;
import com.laishengkai.digitalperson.agent.AgentTool;
import com.laishengkai.digitalperson.dialogue.ModelInvocationOptions;
import com.laishengkai.digitalperson.dialogue.ModelResponseFormat;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;
import com.laishengkai.digitalperson.dialogue.SystemModelMessage;
import com.laishengkai.digitalperson.dialogue.UserModelMessage;
import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * Manually verifies the complete provider-to-tool-to-provider agent loop.
 *
 * <p>The endpoint is available only while the existing LLM connection-test mode
 * is enabled. It accepts no caller-controlled prompt or tool arguments and uses
 * a fixed, side-effect-free tool that returns one constant verification value.</p>
 */
@RestController
@RequestMapping("/internal/agent")
@ConditionalOnBean(AgentExecutor.class)
@ConditionalOnProperty(
        prefix = "digital-person.llm.connection-test",
        name = "enabled",
        havingValue = "true"
)
public final class AgentToolLoopTestController {
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private static final String EXPECTED_RESPONSE = "AGENT_TOOL_OK";
    private static final AgentTool SMOKE_TOOL = new FixedSmokeTool();
    private static final AgentRequest SMOKE_REQUEST = new AgentRequest(
            List.of(
                    new SystemModelMessage(
                            "This is an automated tool-loop test. You must call "
                                    + "agent_smoke_test_value exactly once. After receiving "
                                    + "the tool result, reply with exactly that result and no "
                                    + "other text. Do not invent the result."
                    ),
                    new UserModelMessage("Perform the required tool-loop test now.")
            ),
            new ModelInvocationOptions(
                    0.0,
                    64,
                    List.of(),
                    ModelToolChoice.AUTO,
                    ModelResponseFormat.text()
            ),
            List.of(SMOKE_TOOL),
            3
    );

    private final AgentExecutor agentExecutor;
    private final byte[] expectedToken;

    public AgentToolLoopTestController(
            AgentExecutor agentExecutor,
            LanguageModelProperties properties
    ) {
        this.agentExecutor = Objects.requireNonNull(
                agentExecutor,
                "agentExecutor cannot be null"
        );
        this.expectedToken = properties.connectionTest()
                .requiredToken()
                .getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/tool-loop-test")
    public CompletionStage<ResponseEntity<ToolLoopTestResponse>> testToolLoop(
            @RequestHeader(
                    name = INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken
    ) {
        if (!matchesExpectedToken(suppliedToken)) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(new ToolLoopTestResponse(
                                    "UNAUTHORIZED",
                                    null,
                                    null,
                                    null
                            ))
            );
        }

        return agentExecutor.execute(SMOKE_REQUEST)
                .handle((result, error) -> evaluateResult(result, error));
    }

    private static ResponseEntity<ToolLoopTestResponse> evaluateResult(
            AgentResult result,
            Throwable error
    ) {
        if (error != null || result == null) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ToolLoopTestResponse(
                            "DOWN",
                            null,
                            null,
                            null
                    ));
        }

        String modelText = result.text().strip();
        if (result.toolExecutionCount() != 1) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ToolLoopTestResponse(
                            "UNEXPECTED_TOOL_COUNT",
                            modelText,
                            result.modelInvocationCount(),
                            result.toolExecutionCount()
                    ));
        }
        if (!EXPECTED_RESPONSE.equals(modelText)) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ToolLoopTestResponse(
                            "UNEXPECTED_RESPONSE",
                            modelText,
                            result.modelInvocationCount(),
                            result.toolExecutionCount()
                    ));
        }

        return ResponseEntity.ok(new ToolLoopTestResponse(
                "UP",
                modelText,
                result.modelInvocationCount(),
                result.toolExecutionCount()
        ));
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

    public record ToolLoopTestResponse(
            String status,
            String modelResponse,
            Integer modelInvocationCount,
            Integer toolExecutionCount
    ) {
    }

    private static final class FixedSmokeTool implements AgentTool {
        private static final ModelToolSpecification SPECIFICATION =
                new ModelToolSpecification(
                        "agent_smoke_test_value",
                        "Required smoke-test tool. Call exactly once to obtain the "
                                + "verification value that must be returned verbatim.",
                        "{\"type\":\"object\",\"properties\":{},"
                                + "\"additionalProperties\":false}"
                );

        @Override
        public ModelToolSpecification specification() {
            return SPECIFICATION;
        }

        @Override
        public CompletionStage<String> execute(String argumentsJson) {
            Objects.requireNonNull(argumentsJson, "argumentsJson cannot be null");
            return CompletableFuture.completedFuture(EXPECTED_RESPONSE);
        }
    }
}
