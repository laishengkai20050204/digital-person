package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonDetails;
import com.laishengkai.digitalperson.application.PersonDirectoryService;
import com.laishengkai.digitalperson.application.PersonStateDetails;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentity;
import com.laishengkai.digitalperson.person.PersonIdentitySnapshot;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import com.laishengkai.digitalperson.state.StateTransition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Token-protected HTTP boundary for creating and reading persisted persons. */
@RestController
@RequestMapping("/api/persons")
@ConditionalOnBean(PersonDirectoryService.class)
@ConditionalOnProperty(
        prefix = "digital-person.person-api",
        name = "enabled",
        havingValue = "true"
)
public final class PersonController {
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final PersonDirectoryService directoryService;
    private final InternalTokenGuard tokenGuard;

    public PersonController(
            PersonDirectoryService directoryService,
            PersonApiProperties properties
    ) {
        this.directoryService = Objects.requireNonNull(
                directoryService,
                "directoryService cannot be null"
        );
        this.tokenGuard = new InternalTokenGuard(Objects.requireNonNull(
                properties,
                "properties cannot be null"
        ).requiredToken());
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String suppliedToken,
            @RequestBody CreatePersonRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        CreatePersonRequest requested = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        PersonDetails created = directoryService.create(
                requested.toIdentity(),
                requested.toPersonality()
        );
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{personId}")
                .buildAndExpand(created.personId())
                .toUri();
        return ResponseEntity.created(location).body(toResponse(created));
    }

    @GetMapping("/{personId}")
    public ResponseEntity<?> get(
            @PathVariable String personId,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String suppliedToken
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        return ResponseEntity.ok(toResponse(
                directoryService.get(PersonId.parse(personId))
        ));
    }

    @GetMapping("/{personId}/state")
    public ResponseEntity<?> getState(
            @PathVariable String personId,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String suppliedToken
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        return ResponseEntity.ok(toStateResponse(
                directoryService.getState(PersonId.parse(personId))
        ));
    }

    private static PersonResponse toResponse(PersonDetails details) {
        List<EffectResponse> effects = details.activeEffects().stream()
                .map(EffectResponse::from)
                .toList();
        return new PersonResponse(
                details.personId().toString(),
                details.version(),
                IdentityResponse.from(details.identity()),
                PersonalityResponse.from(details.personality()),
                StateResponse.from(details.state()),
                details.personEventCount(),
                details.userEventCount(),
                details.stateLastUpdatedAt(),
                effects.size(),
                effects
        );
    }

    private static PersonStateResponse toStateResponse(PersonStateDetails details) {
        List<EffectResponse> effects = details.activeEffects().stream()
                .map(EffectResponse::from)
                .toList();
        return new PersonStateResponse(
                details.personId().toString(),
                details.version(),
                StateResponse.from(details.state()),
                details.lastUpdatedAt(),
                effects.size(),
                effects
        );
    }

    public record CreatePersonRequest(
            IdentityRequest identity,
            PersonalityRequest personality
    ) {
        public CreatePersonRequest(PersonalityRequest personality) {
            this(null, personality);
        }

        PersonIdentity toIdentity() {
            return identity == null
                    ? PersonIdentity.unspecified()
                    : identity.toDomain();
        }

        Personality toPersonality() {
            if (personality == null) {
                throw new IllegalArgumentException("personality cannot be null");
            }
            return personality.toDomain();
        }
    }

    public record IdentityRequest(
            String displayName,
            LocalDate birthDate,
            String genderIdentity,
            String residence,
            String timeZone,
            String locale,
            List<String> roles,
            String background
    ) {
        PersonIdentity toDomain() {
            return new PersonIdentity(
                    requiredText(displayName, "displayName"),
                    birthDate,
                    genderIdentity,
                    residence,
                    ZoneId.of(requiredText(timeZone, "timeZone")),
                    Locale.forLanguageTag(requiredText(locale, "locale")),
                    roles,
                    background
            );
        }

        private static String requiredText(String value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " cannot be null");
            }
            String normalized = value.strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(name + " cannot be blank");
            }
            return normalized;
        }
    }

    public record PersonalityRequest(
            Double honestyHumility,
            Double emotionality,
            Double extraversion,
            Double agreeableness,
            Double conscientiousness,
            Double openness
    ) {
        Personality toDomain() {
            return new Personality(
                    required(honestyHumility, "honestyHumility"),
                    required(emotionality, "emotionality"),
                    required(extraversion, "extraversion"),
                    required(agreeableness, "agreeableness"),
                    required(conscientiousness, "conscientiousness"),
                    required(openness, "openness")
            );
        }

        private static double required(Double value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " cannot be null");
            }
            return value;
        }
    }

    public record PersonResponse(
            String personId,
            long version,
            IdentityResponse identity,
            PersonalityResponse personality,
            StateResponse state,
            int personEventCount,
            int userEventCount,
            Instant stateLastUpdatedAt,
            int activeEffectCount,
            List<EffectResponse> activeEffects
    ) {
    }

    public record PersonStateResponse(
            String personId,
            long version,
            StateResponse state,
            Instant lastUpdatedAt,
            int activeEffectCount,
            List<EffectResponse> activeEffects
    ) {
    }

    public record EffectResponse(
            String effectId,
            String sourceEventId,
            String type,
            String cause,
            Instant startsAt,
            String endPolicy,
            Instant fixedEndsAt,
            List<TransitionResponse> transitions
    ) {
        static EffectResponse from(RegisteredStateEffect effect) {
            return new EffectResponse(
                    effect.effectId().toString(),
                    effect.sourceEventId() == null
                            ? null
                            : effect.sourceEventId().toString(),
                    effect.type().name(),
                    effect.cause(),
                    effect.startsAt(),
                    effect.endPolicy().name(),
                    effect.fixedEndsAt(),
                    effect.transitions().stream()
                            .map(TransitionResponse::from)
                            .toList()
            );
        }
    }

    public record TransitionResponse(String dimension, double shape) {
        static TransitionResponse from(StateTransition transition) {
            return new TransitionResponse(
                    transition.dimension().name(),
                    transition.shape()
            );
        }
    }

    public record IdentityResponse(
            String displayName,
            LocalDate birthDate,
            Integer age,
            String genderIdentity,
            String residence,
            String timeZone,
            String locale,
            List<String> roles,
            String background
    ) {
        static IdentityResponse from(PersonIdentitySnapshot snapshot) {
            return new IdentityResponse(
                    snapshot.displayName(),
                    snapshot.birthDate(),
                    snapshot.age(),
                    snapshot.genderIdentity(),
                    snapshot.residence(),
                    snapshot.timeZone(),
                    snapshot.locale(),
                    snapshot.roles(),
                    snapshot.background()
            );
        }
    }

    public record PersonalityResponse(
            double honestyHumility,
            double emotionality,
            double extraversion,
            double agreeableness,
            double conscientiousness,
            double openness
    ) {
        static PersonalityResponse from(PersonalitySnapshot snapshot) {
            return new PersonalityResponse(
                    snapshot.honestyHumility(),
                    snapshot.emotionality(),
                    snapshot.extraversion(),
                    snapshot.agreeableness(),
                    snapshot.conscientiousness(),
                    snapshot.openness()
            );
        }
    }

    public record StateResponse(
            double valence,
            double energy,
            double tension,
            double focus,
            double mentalLoad,
            double motivation,
            double fatigue,
            double sleepiness,
            double hunger,
            double loneliness,
            double socialNeed
    ) {
        static StateResponse from(PersonStateSnapshot snapshot) {
            return new StateResponse(
                    snapshot.valence(),
                    snapshot.energy(),
                    snapshot.tension(),
                    snapshot.focus(),
                    snapshot.mentalLoad(),
                    snapshot.motivation(),
                    snapshot.fatigue(),
                    snapshot.sleepiness(),
                    snapshot.hunger(),
                    snapshot.loneliness(),
                    snapshot.socialNeed()
            );
        }
    }

    public record ErrorResponse(String status, String message) {
    }
}
