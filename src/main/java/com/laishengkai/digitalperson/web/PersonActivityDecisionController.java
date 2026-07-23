package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.activity.ActivityLifecycleCommand;
import com.laishengkai.digitalperson.activity.FinishActivityCommand;
import com.laishengkai.digitalperson.activity.StartActivityCommand;
import com.laishengkai.digitalperson.application.PersonActivityDecisionResult;
import com.laishengkai.digitalperson.application.PersonActivityDecisionService;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Token-protected trigger for one autonomous person activity decision cycle. */
@RestController
@RequestMapping("/api/persons/{personId}/activity-decisions")
@ConditionalOnBean(PersonActivityDecisionService.class)
@ConditionalOnProperty(
        prefix = "digital-person.person-api",
        name = "enabled",
        havingValue = "true"
)
public final class PersonActivityDecisionController {
    private final PersonActivityDecisionService decisionService;
    private final Clock clock;
    private final byte[] expectedToken;

    public PersonActivityDecisionController(
            PersonActivityDecisionService decisionService,
            PersonApiProperties properties,
            Clock clock
    ) {
        this.decisionService = Objects.requireNonNull(
                decisionService,
                "decisionService cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.expectedToken = Objects.requireNonNull(
                properties,
                "properties cannot be null"
        ).requiredToken().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public CompletionStage<ResponseEntity<?>> decide(
            @PathVariable String personId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody(required = false) ActivityDecisionRequest request
    ) {
        if (!authorized(suppliedToken)) {
            return unauthorized();
        }
        Instant decisionTime = clock.instant();
        String observation = request == null ? "" : request.observation();
        return decisionService.decide(
                        PersonId.parse(personId),
                        observation,
                        decisionTime
                )
                .thenApply(result -> ResponseEntity.ok(toResponse(result)));
    }

    private boolean authorized(String suppliedToken) {
        return suppliedToken != null && MessageDigest.isEqual(
                expectedToken,
                suppliedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static CompletionStage<ResponseEntity<?>> unauthorized() {
        return CompletableFuture.completedFuture(
                ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new PersonController.ErrorResponse(
                                "UNAUTHORIZED",
                                "Invalid internal token"
                        ))
        );
    }

    private static ActivityDecisionResponse toResponse(
            PersonActivityDecisionResult result
    ) {
        List<RegisteredStateEffect> effects = result.stateEvolutionContext()
                .effects()
                .values()
                .stream()
                .sorted(Comparator.comparing(RegisteredStateEffect::effectId))
                .toList();
        return new ActivityDecisionResponse(
                result.personId().toString(),
                result.decisionTime(),
                result.plan().nextReviewMinutes(),
                result.nextReviewAt(),
                result.plan().commands().stream()
                        .map(PersonActivityDecisionController::toCommandResponse)
                        .toList(),
                result.startedEvents().stream()
                        .map(EventResponse::from)
                        .toList(),
                result.finishedEvents().stream()
                        .map(EventResponse::from)
                        .toList(),
                PersonController.StateResponse.from(result.state()),
                result.stateEvolutionContext().lastUpdatedAt(),
                effects.size(),
                effects.stream()
                        .map(PersonController.EffectResponse::from)
                        .toList()
        );
    }

    private static CommandResponse toCommandResponse(ActivityLifecycleCommand command) {
        return switch (command) {
            case StartActivityCommand start -> new CommandResponse(
                    start.action().name(),
                    null,
                    null,
                    start.activityType().name(),
                    start.title(),
                    start.location(),
                    start.participants(),
                    start.notes()
            );
            case FinishActivityCommand finish -> new CommandResponse(
                    finish.action().name(),
                    finish.eventId().toString(),
                    finish.reason().name(),
                    null,
                    null,
                    null,
                    null,
                    null
            );
        };
    }

    public record ActivityDecisionRequest(String observation) {
    }

    public record ActivityDecisionResponse(
            String personId,
            Instant decisionTime,
            int nextReviewMinutes,
            Instant nextReviewAt,
            List<CommandResponse> commands,
            List<EventResponse> startedEvents,
            List<EventResponse> finishedEvents,
            PersonController.StateResponse state,
            Instant stateLastUpdatedAt,
            int activeEffectCount,
            List<PersonController.EffectResponse> activeEffects
    ) {
    }

    public record CommandResponse(
            String action,
            String eventId,
            String reason,
            String activityType,
            String title,
            String location,
            List<String> participants,
            String notes
    ) {
    }

    public record EventResponse(
            String eventId,
            String activityType,
            String channel,
            String title,
            String location,
            Instant startTime,
            Instant endTime,
            String endReason,
            List<String> participants,
            String notes
    ) {
        static EventResponse from(PersonEvent event) {
            return new EventResponse(
                    event.getId().toString(),
                    event.getActivityType().name(),
                    event.getChannel().name(),
                    event.getTitle(),
                    event.getLocation(),
                    event.getStartTime(),
                    event.getEndTime().orElse(null),
                    event.getEndReason().map(Enum::name).orElse(null),
                    event.getParticipants(),
                    event.getNotes()
            );
        }
    }
}
