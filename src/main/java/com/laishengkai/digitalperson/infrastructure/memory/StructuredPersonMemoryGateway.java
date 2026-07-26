package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryEntityMatch;
import com.laishengkai.digitalperson.memory.MemoryEntityResolutionQuery;
import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.memory.StructuredMemoryQuery;
import com.laishengkai.digitalperson.memory.StructuredMemoryQueryPlan;
import com.laishengkai.digitalperson.memory.StructuredMemoryQueryPlanner;
import com.laishengkai.digitalperson.memory.StructuredMemoryRepository;
import com.laishengkai.digitalperson.memory.StructuredMemorySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/** Converts typed MySQL facts into the provider-neutral model memory context. */
public final class StructuredPersonMemoryGateway implements StructuredMemorySource {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            StructuredPersonMemoryGateway.class
    );
    private static final int ENTITY_MATCH_LIMIT = 5;

    private final StructuredMemoryRepository repository;
    private final StructuredMemoryQueryPlanner queryPlanner;
    private final StructuredMemoryProperties properties;
    private final Clock clock;

    public StructuredPersonMemoryGateway(
            StructuredMemoryRepository repository,
            Clock clock
    ) {
        this(
                repository,
                new HeuristicStructuredMemoryQueryPlanner(),
                new StructuredMemoryProperties(true, 0.60, 300),
                clock
        );
    }

    public StructuredPersonMemoryGateway(
            StructuredMemoryRepository repository,
            StructuredMemoryQueryPlanner queryPlanner,
            StructuredMemoryProperties properties,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository cannot be null"
        );
        this.queryPlanner = Objects.requireNonNull(
                queryPlanner,
                "queryPlanner cannot be null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @Override
    public CompletionStage<PersonMemoryContext> retrieve(PersonMemoryQuery query) {
        PersonMemoryQuery request = Objects.requireNonNull(
                query,
                "query cannot be null"
        );
        CompletionStage<StructuredMemoryQueryPlan> planStage = safePlan(request);
        return planStage.thenCompose(plan -> resolveEntities(request, plan)
                        .thenCompose(entityIds -> repository.search(
                                toStructuredQuery(request, plan, entityIds)
                        )))
                .handle((matches, failure) -> {
                    if (failure != null) {
                        LOGGER.warn(
                                "Structured memory retrieval failed; continuing without structured facts: personId={}",
                                request.personId(),
                                failure
                        );
                        return PersonMemoryContext.unavailable();
                    }
                    List<MemoryItem> items = Objects.requireNonNull(
                            matches,
                            "repository result cannot be null"
                    ).stream()
                            .map(match -> new MemoryItem(
                                    "structured:" + match.fact().factId(),
                                    match.fact().section(),
                                    match.fact().statement(),
                                    match.relevance(),
                                    match.fact().createdAt(),
                                    match.fact().updatedAt()
                            ))
                            .toList();
                    return PersonMemoryContext.available(items);
                });
    }

    private CompletionStage<StructuredMemoryQueryPlan> safePlan(
            PersonMemoryQuery request
    ) {
        CompletionStage<StructuredMemoryQueryPlan> stage;
        try {
            stage = queryPlanner.plan(request);
        } catch (RuntimeException error) {
            stage = CompletableFuture.failedFuture(error);
        }
        if (stage == null) {
            stage = CompletableFuture.failedFuture(
                    new IllegalStateException("query planner returned null stage")
            );
        }
        return stage.exceptionally(ignored -> new StructuredMemoryQueryPlan(
                request.sections(),
                Set.of(),
                Set.of(),
                Set.of(),
                request.relevanceQuery()
        ));
    }

    private CompletionStage<Set<String>> resolveEntities(
            PersonMemoryQuery request,
            StructuredMemoryQueryPlan plan
    ) {
        if (plan.entityMention().isBlank()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        MemoryEntityResolutionQuery resolutionQuery = new MemoryEntityResolutionQuery(
                request.personId(),
                plan.entityMention(),
                plan.entityTypes(),
                request.relevanceQuery(),
                properties.minimumEntitySimilarity(),
                ENTITY_MATCH_LIMIT
        );
        CompletionStage<List<MemoryEntityMatch>> stage;
        try {
            stage = repository.resolve(resolutionQuery);
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(Set.of());
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(Set.of());
        }
        return stage.exceptionally(ignored -> List.of())
                .thenApply(matches -> matches.stream()
                        .map(MemoryEntityMatch::entity)
                        .map(entity -> entity.entityId())
                        .collect(Collectors.toUnmodifiableSet()));
    }

    private StructuredMemoryQuery toStructuredQuery(
            PersonMemoryQuery request,
            StructuredMemoryQueryPlan plan,
            Set<String> entityIds
    ) {
        return new StructuredMemoryQuery(
                request.personId(),
                plan.sections(),
                plan.domains(),
                entityIds,
                plan.predicates(),
                clock.instant(),
                request.relevanceQuery(),
                request.maxItems()
        );
    }
}
