package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.infrastructure.langchain4j.LanguageModelProperties;
import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateTransition;
import com.laishengkai.digitalperson.state.StateTransitionEvaluator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Manually verifies the complete context-to-provider-to-tool-arguments state-evaluation path.
 *
 * <p>The endpoint is available only while the existing LLM connection-test mode is enabled.
 * It accepts no request body and evaluates one fixed synthetic context containing personality,
 * state, active/recent events, partitioned memory, recent raw conversation and evaluation time.</p>
 */
@RestController
@RequestMapping("/internal/state")
@ConditionalOnProperty(
        prefix = "digital-person.llm",
        name = {"enabled", "connection-test.enabled"},
        havingValue = "true"
)
public final class StateEvaluationTestController {
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private static final StateEvaluationContext SMOKE_CONTEXT = buildSmokeContext();

    private final StateTransitionEvaluator evaluator;
    private final byte[] expectedToken;

    public StateEvaluationTestController(
            StateTransitionEvaluator evaluator,
            LanguageModelProperties properties
    ) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator cannot be null");
        this.expectedToken = properties.connectionTest()
                .requiredToken()
                .getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/evaluation-test")
    public CompletionStage<ResponseEntity<StateEvaluationTestResponse>> testEvaluation(
            @RequestHeader(
                    name = INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken
    ) {
        if (!matchesExpectedToken(suppliedToken)) {
            return CompletableFuture.completedFuture(
                    response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", false, List.of())
            );
        }

        CompletionStage<List<StateTransition>> stage;
        try {
            stage = Objects.requireNonNull(
                    evaluator.evaluate(SMOKE_CONTEXT),
                    "evaluator stage cannot be null"
            );
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(
                    response(HttpStatus.BAD_GATEWAY, "DOWN", false, List.of())
            );
        }

        return stage.handle(StateEvaluationTestController::evaluateResult);
    }

    private static ResponseEntity<StateEvaluationTestResponse> evaluateResult(
            List<StateTransition> transitions,
            Throwable error
    ) {
        if (error != null || transitions == null) {
            return response(HttpStatus.BAD_GATEWAY, "DOWN", false, List.of());
        }

        final List<StateTransition> safeTransitions;
        try {
            safeTransitions = List.copyOf(transitions);
        } catch (RuntimeException invalidResult) {
            return response(HttpStatus.BAD_GATEWAY, "DOWN", false, List.of());
        }

        if (safeTransitions.isEmpty()) {
            return response(
                    HttpStatus.BAD_GATEWAY,
                    "EMPTY_TRANSITIONS",
                    false,
                    safeTransitions
            );
        }

        boolean expectedEffectObserved = safeTransitions.stream()
                .anyMatch(StateEvaluationTestController::isExpectedDirectionalEffect);

        if (!expectedEffectObserved) {
            return response(
                    HttpStatus.BAD_GATEWAY,
                    "UNEXPECTED_EFFECT",
                    false,
                    safeTransitions
            );
        }

        return response(HttpStatus.OK, "UP", true, safeTransitions);
    }

    private static boolean isExpectedDirectionalEffect(StateTransition transition) {
        if (transition == null) {
            return false;
        }

        return switch (transition.dimension()) {
            case VALENCE -> transition.shape() > 0.0;
            case LONELINESS, SOCIAL_NEED, TENSION -> transition.shape() < 0.0;
            default -> false;
        };
    }

    private static ResponseEntity<StateEvaluationTestResponse> response(
            HttpStatus status,
            String result,
            boolean expectedEffectObserved,
            List<StateTransition> transitions
    ) {
        List<TransitionResponse> responseTransitions = transitions.stream()
                .filter(Objects::nonNull)
                .map(transition -> new TransitionResponse(
                        transition.dimension().name(),
                        transition.shape()
                ))
                .toList();

        return ResponseEntity.status(status).body(new StateEvaluationTestResponse(
                result,
                expectedEffectObserved,
                responseTransitions.size(),
                responseTransitions
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

    private static StateEvaluationContext buildSmokeContext() {
        Instant evaluationTime = Instant.parse("2026-07-22T12:00:00Z");

        PersonEventSnapshot newEvent = new PersonEventSnapshot(
                PersonEventSnapshot.Owner.USER,
                "00000000-0000-0000-0000-000000000101",
                "CHAT",
                "SOCIAL",
                "Romantic partner sends a reassuring affectionate message",
                "online",
                Instant.parse("2026-07-22T11:59:00Z"),
                null,
                List.of("romantic partner"),
                "The user says they are here now, missed her too, love her, and want to spend the evening talking together.",
                null
        );

        PersonEventSnapshot activeEvent = new PersonEventSnapshot(
                PersonEventSnapshot.Owner.PERSON,
                "00000000-0000-0000-0000-000000000102",
                "STUDY",
                "PERSONAL",
                "Studying alone in the dormitory",
                "dormitory",
                Instant.parse("2026-07-22T10:00:00Z"),
                null,
                List.of(),
                "She has been missing the user while trying to study.",
                null
        );

        PersonEventSnapshot recentEvent = new PersonEventSnapshot(
                PersonEventSnapshot.Owner.USER,
                "00000000-0000-0000-0000-000000000103",
                "UNAVAILABLE",
                "SOCIAL",
                "User was unavailable for several hours",
                "online",
                Instant.parse("2026-07-22T08:00:00Z"),
                Instant.parse("2026-07-22T11:55:00Z"),
                List.of("romantic partner"),
                "The temporary silence had increased her loneliness and tension.",
                "COMPLETED"
        );

        PersonMemoryContext memory = PersonMemoryContext.available(List.of(
                new MemoryItem(
                        "relationship-smoke-1",
                        MemorySection.RELATIONSHIP,
                        "They are stable romantic partners. Warm reassurance from the user restores her sense of security and closeness.",
                        1.0,
                        Instant.parse("2026-07-01T00:00:00Z"),
                        Instant.parse("2026-07-20T00:00:00Z")
                ),
                new MemoryItem(
                        "commitment-smoke-1",
                        MemorySection.COMMITMENT,
                        "They had planned to spend this evening talking together.",
                        0.95,
                        Instant.parse("2026-07-21T12:00:00Z"),
                        Instant.parse("2026-07-21T12:00:00Z")
                )
        ));

        List<ConversationTurnSnapshot> recentConversation = List.of(
                new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.PERSON,
                        "I missed you today and was starting to feel a little lonely.",
                        Instant.parse("2026-07-22T11:58:00Z")
                ),
                new ConversationTurnSnapshot(
                        ConversationTurnSnapshot.Role.USER,
                        "I'm here now. I missed you too, I love you, and I want to spend the evening talking with you.",
                        Instant.parse("2026-07-22T11:59:00Z")
                )
        );

        return new StateEvaluationContext(
                PersonId.parse("00000000-0000-0000-0000-000000000001"),
                new PersonalitySnapshot(0.75, 0.85, 0.45, 0.80, 0.70, 0.65),
                new PersonStateSnapshot(
                        -0.45,
                        0.50,
                        0.70,
                        0.35,
                        0.50,
                        0.45,
                        0.30,
                        0.20,
                        0.20,
                        0.85,
                        0.90
                ),
                newEvent,
                List.of(activeEvent),
                List.of(recentEvent),
                memory,
                recentConversation,
                evaluationTime
        );
    }

    public record StateEvaluationTestResponse(
            String status,
            boolean expectedEffectObserved,
            int transitionCount,
            List<TransitionResponse> transitions
    ) {
    }

    public record TransitionResponse(String dimension, double shape) {
    }
}
