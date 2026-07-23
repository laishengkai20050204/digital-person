package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonEventCommandResult;
import com.laishengkai.digitalperson.application.PersonEventCommandService;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Token-protected HTTP boundary for person-owned event commands. */
@RestController
@RequestMapping("/api/persons/{personId}/events")
@ConditionalOnProperty(
        prefix = "digital-person.person-api",
        name = "enabled",
        havingValue = "true"
)
public final class PersonEventController {
    private final PersonEventCommandService commandService;
    private final Clock clock;
    private final InternalTokenGuard tokenGuard;

    public PersonEventController(
            PersonEventCommandService commandService,
            PersonApiProperties properties,
            Clock clock
    ) {
        this.commandService = Objects.requireNonNull(
                commandService,
                "commandService cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.tokenGuard = new InternalTokenGuard(Objects.requireNonNull(
                properties,
                "properties cannot be null"
        ).requiredToken());
    }

    @PostMapping("/realtime")
    public CompletionStage<ResponseEntity<?>> startRealtime(
            @PathVariable String personId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody RealtimeEventRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        Instant commandTime = clock.instant();
        PersonEvent event = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).toDomain(commandTime);
        return commandService.start(PersonId.parse(personId), event, commandTime)
                .thenApply(result -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(toResponse(result)));
    }

    @PostMapping("/{eventId}/finish")
    public CompletionStage<ResponseEntity<?>> finish(
            @PathVariable String personId,
            @PathVariable String eventId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody FinishEventRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        EventEndReason reason = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).toReason();
        return commandService.finish(
                        PersonId.parse(personId),
                        EventId.parse(eventId),
                        reason,
                        clock.instant()
                )
                .thenApply(result -> ResponseEntity.ok(toResponse(result)));
    }

    @PostMapping("/history")
    public CompletionStage<ResponseEntity<?>> recordHistorical(
            @PathVariable String personId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody HistoricalEventRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        PersonEvent event = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).toDomain();
        return commandService.recordHistorical(
                        PersonId.parse(personId),
                        event,
                        clock.instant()
                )
                .thenApply(result -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(toResponse(result)));
    }

    private static EventCommandResponse toResponse(PersonEventCommandResult result) {
        PersonEvent event = result.event();
        List<RegisteredStateEffect> sortedEffects = result.stateEvolutionContext()
                .effects()
                .values()
                .stream()
                .sorted(Comparator.comparing(RegisteredStateEffect::effectId))
                .toList();
        List<PersonController.EffectResponse> effects = sortedEffects.stream()
                .map(PersonController.EffectResponse::from)
                .toList();
        return new EventCommandResponse(
                result.personId().toString(),
                event.getId().toString(),
                event.getActivityType().name(),
                event.getChannel().name(),
                event.getTitle(),
                event.getLocation(),
                event.getStartTime(),
                event.getEndTime().orElse(null),
                event.getEndReason().map(Enum::name).orElse(null),
                event.getParticipants(),
                event.getNotes(),
                PersonController.StateResponse.from(result.state()),
                result.stateEvolutionContext().lastUpdatedAt(),
                effects.size(),
                effects
        );
    }

    private static ActivityType parseActivityType(String value) {
        String normalized = requireText(value, "activityType").toUpperCase(Locale.ROOT);
        try {
            return ActivityType.valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown activityType: " + value, error);
        }
    }

    private static EventEndReason parseEndReason(String value) {
        String normalized = requireText(value, "reason").toUpperCase(Locale.ROOT);
        try {
            return EventEndReason.valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown event end reason: " + value, error);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
        return value;
    }

    public record RealtimeEventRequest(
            String activityType,
            String title,
            String location,
            List<String> participants,
            String notes
    ) {
        PersonEvent toDomain(Instant commandTime) {
            return new PersonEvent(
                    EventId.random(),
                    parseActivityType(activityType),
                    requireText(title, "title"),
                    location,
                    TimeRange.openEnded(required(commandTime, "commandTime")),
                    participants,
                    notes
            );
        }
    }

    public record HistoricalEventRequest(
            String activityType,
            String title,
            String location,
            Instant startTime,
            Instant endTime,
            List<String> participants,
            String notes
    ) {
        PersonEvent toDomain() {
            return new PersonEvent(
                    EventId.random(),
                    parseActivityType(activityType),
                    requireText(title, "title"),
                    location,
                    TimeRange.closed(
                            required(startTime, "startTime"),
                            required(endTime, "endTime")
                    ),
                    participants,
                    notes
            );
        }
    }

    public record FinishEventRequest(String reason) {
        EventEndReason toReason() {
            EventEndReason parsed = parseEndReason(reason);
            if (parsed == EventEndReason.REPLACED) {
                throw new IllegalArgumentException(
                        "REPLACED is reserved for realtime replacement"
                );
            }
            return parsed;
        }
    }

    public record EventCommandResponse(
            String personId,
            String eventId,
            String activityType,
            String channel,
            String title,
            String location,
            Instant startTime,
            Instant endTime,
            String endReason,
            List<String> participants,
            String notes,
            PersonController.StateResponse state,
            Instant stateLastUpdatedAt,
            int activeEffectCount,
            List<PersonController.EffectResponse> activeEffects
    ) {
    }
}
