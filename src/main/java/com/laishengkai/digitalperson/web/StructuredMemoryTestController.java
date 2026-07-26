package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.memory.MemoryEntity;
import com.laishengkai.digitalperson.memory.MemoryEntityMatch;
import com.laishengkai.digitalperson.memory.MemoryEntityResolutionQuery;
import com.laishengkai.digitalperson.memory.MemoryEntityType;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.StructuredMemoryAliasDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryEntityDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryFact;
import com.laishengkai.digitalperson.memory.StructuredMemoryFactDraft;
import com.laishengkai.digitalperson.memory.StructuredMemoryMatch;
import com.laishengkai.digitalperson.memory.StructuredMemoryQuery;
import com.laishengkai.digitalperson.memory.StructuredMemoryRepository;
import com.laishengkai.digitalperson.person.PersonId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/** Token-protected verification API for typed facts, aliases and entity resolution. */
@RestController
@RequestMapping("/internal/memory-test")
@ConditionalOnBean(StructuredMemoryRepository.class)
@ConditionalOnProperty(
        prefix = "digital-person.memory.test-api",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(MemoryTestApiProperties.class)
public final class StructuredMemoryTestController {
    private final StructuredMemoryRepository repository;
    private final Clock clock;
    private final InternalTokenGuard tokenGuard;

    public StructuredMemoryTestController(
            StructuredMemoryRepository repository,
            Clock clock,
            MemoryTestApiProperties properties
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.tokenGuard = new InternalTokenGuard(Objects.requireNonNull(
                properties,
                "properties cannot be null"
        ).requiredToken());
    }

    @PostMapping("/persons/{personId}/structured/entities")
    public CompletionStage<ResponseEntity<EntityResponse>> upsertEntity(
            @PathVariable String personId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody EntityRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        PersonId parsedPersonId = PersonId.parse(personId);
        StructuredMemoryEntityDraft draft = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).toDomain(parsedPersonId, clock.instant());
        return providerStage(repository.upsertEntity(draft))
                .thenApply(entity -> ResponseEntity.ok(EntityResponse.from(entity)));
    }

    @PostMapping("/structured/entities/{entityId}/aliases")
    public CompletionStage<ResponseEntity<Void>> addAlias(
            @PathVariable String entityId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody AliasRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        StructuredMemoryAliasDraft draft = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).toDomain(entityId, clock.instant());
        return providerStage(repository.addAlias(draft))
                .thenApply(ignored -> ResponseEntity.noContent().build());
    }

    @PostMapping("/persons/{personId}/structured/entities/resolve")
    public CompletionStage<ResponseEntity<EntityResolutionResponse>> resolveEntity(
            @PathVariable String personId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody EntityResolutionRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        PersonId parsedPersonId = PersonId.parse(personId);
        MemoryEntityResolutionQuery query = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).toDomain(parsedPersonId);
        return providerStage(repository.resolve(query))
                .thenApply(matches -> ResponseEntity.ok(new EntityResolutionResponse(
                        parsedPersonId.toString(),
                        matches.stream().map(EntityMatchResponse::from).toList()
                )));
    }

    @PostMapping("/persons/{personId}/structured/facts")
    public CompletionStage<ResponseEntity<FactResponse>> upsertFact(
            @PathVariable String personId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody FactRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        PersonId parsedPersonId = PersonId.parse(personId);
        StructuredMemoryFactDraft draft = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).toDomain(parsedPersonId, clock.instant());
        return providerStage(repository.upsertFact(draft))
                .thenApply(fact -> ResponseEntity.ok(FactResponse.from(fact)));
    }

    @PostMapping("/persons/{personId}/structured/search")
    public CompletionStage<ResponseEntity<FactSearchResponse>> search(
            @PathVariable String personId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody FactSearchRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        PersonId parsedPersonId = PersonId.parse(personId);
        StructuredMemoryQuery query = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).toDomain(parsedPersonId, clock.instant());
        return providerStage(repository.search(query))
                .thenApply(matches -> ResponseEntity.ok(new FactSearchResponse(
                        parsedPersonId.toString(),
                        matches.stream().map(FactMatchResponse::from).toList()
                )));
    }

    private static <T> CompletionStage<T> providerStage(CompletionStage<T> stage) {
        return Objects.requireNonNull(stage, "stage cannot be null")
                .handle((value, failure) -> {
                    if (failure != null) {
                        throw new CompletionException(new MemoryTestProviderException(
                                "Structured memory request failed",
                                unwrap(failure)
                        ));
                    }
                    return value;
                });
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String requiredText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }

    private static MemorySection section(String value) {
        try {
            return MemorySection.valueOf(requiredText(value, "section")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown memory section: " + value);
        }
    }

    private static MemoryEntityType entityType(String value) {
        try {
            return MemoryEntityType.valueOf(requiredText(value, "entityType")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown memory entity type: " + value);
        }
    }

    public record EntityRequest(
            String entityType,
            String canonicalName,
            String description,
            Instant observedAt
    ) {
        StructuredMemoryEntityDraft toDomain(PersonId personId, Instant defaultTime) {
            return new StructuredMemoryEntityDraft(
                    personId,
                    StructuredMemoryTestController.entityType(entityType),
                    canonicalName,
                    description,
                    observedAt == null ? defaultTime : observedAt
            );
        }
    }

    public record AliasRequest(
            String alias,
            String source,
            Double confidence,
            Instant observedAt
    ) {
        StructuredMemoryAliasDraft toDomain(String entityId, Instant defaultTime) {
            return new StructuredMemoryAliasDraft(
                    requiredText(entityId, "entityId"),
                    alias,
                    source,
                    confidence == null ? 1.0 : confidence,
                    observedAt == null ? defaultTime : observedAt
            );
        }
    }

    public record EntityResolutionRequest(
            String mention,
            Set<String> entityTypes,
            String context,
            Double minimumSimilarity,
            Integer maxCandidates
    ) {
        MemoryEntityResolutionQuery toDomain(PersonId personId) {
            Set<MemoryEntityType> types = entityTypes == null
                    ? Set.of()
                    : entityTypes.stream()
                            .map(StructuredMemoryTestController::entityType)
                            .collect(Collectors.toUnmodifiableSet());
            return new MemoryEntityResolutionQuery(
                    personId,
                    mention,
                    types,
                    context,
                    minimumSimilarity == null ? 0.60 : minimumSimilarity,
                    maxCandidates == null ? 5 : maxCandidates
            );
        }
    }

    public record FactRequest(
            String section,
            String domain,
            String subjectEntityId,
            String predicate,
            String objectEntityId,
            String textValue,
            String statement,
            Double confidence,
            Double importance,
            Instant validFrom,
            Instant validUntil,
            Instant observedAt
    ) {
        StructuredMemoryFactDraft toDomain(PersonId personId, Instant defaultTime) {
            return new StructuredMemoryFactDraft(
                    personId,
                    StructuredMemoryTestController.section(section),
                    domain,
                    subjectEntityId,
                    predicate,
                    objectEntityId,
                    textValue,
                    statement,
                    confidence == null ? 1.0 : confidence,
                    importance == null ? 0.5 : importance,
                    validFrom,
                    validUntil,
                    observedAt == null ? defaultTime : observedAt
            );
        }
    }

    public record FactSearchRequest(
            Set<String> sections,
            Set<String> domains,
            Set<String> entityIds,
            Set<String> predicates,
            Instant validAt,
            String query,
            Integer maxItems
    ) {
        StructuredMemoryQuery toDomain(PersonId personId, Instant defaultTime) {
            Set<MemorySection> mappedSections = sections == null
                    ? Set.of()
                    : sections.stream()
                            .map(StructuredMemoryTestController::section)
                            .collect(Collectors.toUnmodifiableSet());
            return new StructuredMemoryQuery(
                    personId,
                    mappedSections,
                    domains,
                    entityIds,
                    predicates,
                    validAt == null ? defaultTime : validAt,
                    query,
                    maxItems == null ? 10 : maxItems
            );
        }
    }

    public record EntityResponse(
            String entityId,
            String personId,
            String entityType,
            String canonicalName,
            String description,
            Instant createdAt,
            Instant updatedAt
    ) {
        static EntityResponse from(MemoryEntity entity) {
            return new EntityResponse(
                    entity.entityId(),
                    entity.personId().toString(),
                    entity.entityType().name(),
                    entity.canonicalName(),
                    entity.description(),
                    entity.createdAt(),
                    entity.updatedAt()
            );
        }
    }

    public record EntityMatchResponse(
            EntityResponse entity,
            String matchedAlias,
            double similarity
    ) {
        static EntityMatchResponse from(MemoryEntityMatch match) {
            return new EntityMatchResponse(
                    EntityResponse.from(match.entity()),
                    match.matchedAlias(),
                    match.similarity()
            );
        }
    }

    public record EntityResolutionResponse(
            String personId,
            List<EntityMatchResponse> matches
    ) {
    }

    public record FactResponse(
            String factId,
            String personId,
            String section,
            String domain,
            String subjectEntityId,
            String predicate,
            String objectEntityId,
            String textValue,
            String statement,
            double confidence,
            double importance,
            int evidenceCount,
            Instant validFrom,
            Instant validUntil,
            Instant lastConfirmedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        static FactResponse from(StructuredMemoryFact fact) {
            return new FactResponse(
                    fact.factId(),
                    fact.personId().toString(),
                    fact.section().name(),
                    fact.domain(),
                    fact.subjectEntityId(),
                    fact.predicate(),
                    fact.objectEntityId(),
                    fact.textValue(),
                    fact.statement(),
                    fact.confidence(),
                    fact.importance(),
                    fact.evidenceCount(),
                    fact.validFrom(),
                    fact.validUntil(),
                    fact.lastConfirmedAt(),
                    fact.createdAt(),
                    fact.updatedAt()
            );
        }
    }

    public record FactMatchResponse(FactResponse fact, double relevance) {
        static FactMatchResponse from(StructuredMemoryMatch match) {
            return new FactMatchResponse(FactResponse.from(match.fact()), match.relevance());
        }
    }

    public record FactSearchResponse(
            String personId,
            List<FactMatchResponse> matches
    ) {
    }
}
