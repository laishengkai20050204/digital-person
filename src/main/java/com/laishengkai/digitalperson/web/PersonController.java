package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonDetails;
import com.laishengkai.digitalperson.application.PersonDirectoryService;
import com.laishengkai.digitalperson.application.PersonStateDetails;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
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
    private final byte[] expectedToken;

    public PersonController(
            PersonDirectoryService directoryService,
            PersonApiProperties properties
    ) {
        this.directoryService = Objects.requireNonNull(
                directoryService,
                "directoryService cannot be null"
        );
        this.expectedToken = Objects.requireNonNull(
                properties,
                "properties cannot be null"
        ).requiredToken().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String suppliedToken,
            @RequestBody CreatePersonRequest request
    ) {
        if (!authorized(suppliedToken)) {
            return unauthorized();
        }
        PersonDetails created = directoryService.create(
                Objects.requireNonNull(request, "request cannot be null").toPersonality()
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
        if (!authorized(suppliedToken)) {
            return unauthorized();
        }
        return ResponseEntity.ok(toResponse(
                directoryService.get(PersonId.parse(personId))
        ));
    }

    @GetMapping("/{personId}/state")
    public ResponseEntity<?> getState(
            @PathVariable String personId,
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String suppliedToken
    ) {
        if (!authorized(suppliedToken)) {
            return unauthorized();
        }
        return ResponseEntity.ok(toStateResponse(
                directoryService.getState(PersonId.parse(personId))
        ));
    }

    private boolean authorized(String suppliedToken) {
        return suppliedToken != null && MessageDigest.isEqual(
                expectedToken,
                suppliedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ResponseEntity<ErrorResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", "Invalid internal token"));
    }

    private static PersonResponse toResponse(PersonDetails details) {
        return new PersonResponse(
                details.personId().toString(),
                details.version(),
                PersonalityResponse.from(details.personality()),
                StateResponse.from(details.state()),
                details.personEventCount(),
                details.userEventCount(),
                details.stateLastUpdatedAt(),
                channelNames(details.activeEffectChannels().stream()
                        .map(Enum::name)
                        .toList())
        );
    }

    private static PersonStateResponse toStateResponse(PersonStateDetails details) {
        return new PersonStateResponse(
                details.personId().toString(),
                details.version(),
                StateResponse.from(details.state()),
                details.lastUpdatedAt(),
                channelNames(details.activeEffectChannels().stream()
                        .map(Enum::name)
                        .toList())
        );
    }

    private static List<String> channelNames(List<String> channels) {
        return channels.stream().sorted().toList();
    }

    public record CreatePersonRequest(PersonalityRequest personality) {
        Personality toPersonality() {
            return Objects.requireNonNull(
                    personality,
                    "personality cannot be null"
            ).toDomain();
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
            return Objects.requireNonNull(value, name + " cannot be null");
        }
    }

    public record PersonResponse(
            String personId,
            long version,
            PersonalityResponse personality,
            StateResponse state,
            int personEventCount,
            int userEventCount,
            Instant stateLastUpdatedAt,
            List<String> activeEffectChannels
    ) {
    }

    public record PersonStateResponse(
            String personId,
            long version,
            StateResponse state,
            Instant lastUpdatedAt,
            List<String> activeEffectChannels
    ) {
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
