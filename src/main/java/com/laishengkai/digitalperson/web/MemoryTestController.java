package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.MemoryMessage;
import com.laishengkai.digitalperson.memory.MemoryMessageRole;
import com.laishengkai.digitalperson.memory.MemoryMutation;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest;
import com.laishengkai.digitalperson.person.PersonId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Temporary token-protected HTTP boundary for end-to-end Mem0 verification. */
@RestController
@RequestMapping("/internal/memory-test")
@ConditionalOnProperty(
        prefix = "digital-person.memory.test-api",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(MemoryTestApiProperties.class)
public final class MemoryTestController {
    private final PersonMemoryStore memoryStore;
    private final PersonMemoryGateway memoryGateway;
    private final InternalTokenGuard tokenGuard;

    @Autowired
    public MemoryTestController(
            @Qualifier("mem0PersonMemoryStore") ObjectProvider<PersonMemoryStore> memoryStoreProvider,
            @Qualifier("mem0PersonMemoryGateway") ObjectProvider<PersonMemoryGateway> memoryGatewayProvider,
            MemoryTestApiProperties properties
    ) {
        this(
                memoryStoreProvider.getIfAvailable(),
                memoryGatewayProvider.getIfAvailable(),
                properties
        );
    }

    MemoryTestController(
            PersonMemoryStore memoryStore,
            PersonMemoryGateway memoryGateway,
            MemoryTestApiProperties properties
    ) {
        this.memoryStore = memoryStore;
        this.memoryGateway = memoryGateway;
        this.tokenGuard = new InternalTokenGuard(Objects.requireNonNull(
                properties,
                "properties cannot be null"
        ).requiredToken());
    }

    @PostMapping("/persons/{personId}/memories")
    public CompletionStage<ResponseEntity<WriteResponse>> write(
            @PathVariable String personId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody WriteRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        PersonId parsedPersonId = PersonId.parse(personId);
        PersonMemoryWriteRequest command = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).toDomain(parsedPersonId);
        return providerStage(requiredStore().add(command))
                .thenApply(mutations -> ResponseEntity.ok(new WriteResponse(
                        parsedPersonId.toString(),
                        mutations.stream().map(MutationResponse::from).toList()
                )));
    }

    @PostMapping("/persons/{personId}/search")
    public CompletionStage<ResponseEntity<SearchResponse>> search(
            @PathVariable String personId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody SearchRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        PersonId parsedPersonId = PersonId.parse(personId);
        PersonMemoryQuery query = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).toDomain(parsedPersonId);
        return providerStage(requiredGateway().retrieve(query))
                .thenApply(context -> ResponseEntity.ok(SearchResponse.from(
                        parsedPersonId,
                        context
                )));
    }

    @DeleteMapping("/memories/{memoryId}")
    public CompletionStage<ResponseEntity<Void>> delete(
            @PathVariable String memoryId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        String normalizedMemoryId = requiredText(memoryId, "memoryId");
        return providerStage(requiredStore().delete(normalizedMemoryId))
                .thenApply(ignored -> ResponseEntity.noContent().build());
    }

    private PersonMemoryStore requiredStore() {
        if (memoryStore == null) {
            throw new MemoryTestUnavailableException(
                    "Mem0 writing is unavailable; enable digital-person.memory.mem0.enabled"
            );
        }
        return memoryStore;
    }

    private PersonMemoryGateway requiredGateway() {
        if (memoryGateway == null) {
            throw new MemoryTestUnavailableException(
                    "Mem0 retrieval is unavailable; enable digital-person.memory.mem0.retrieval-enabled"
            );
        }
        return memoryGateway;
    }

    private static <T> CompletionStage<T> providerStage(CompletionStage<T> stage) {
        return Objects.requireNonNull(stage, "stage cannot be null")
                .handle((value, failure) -> {
                    if (failure != null) {
                        throw new CompletionException(new MemoryTestProviderException(
                                "Mem0 request failed",
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

    public record WriteRequest(
            List<MessageRequest> messages,
            Map<String, String> metadata,
            Boolean infer
    ) {
        PersonMemoryWriteRequest toDomain(PersonId personId) {
            List<MemoryMessage> mappedMessages = Objects.requireNonNull(
                    messages,
                    "messages cannot be null"
            ).stream().map(MessageRequest::toDomain).toList();
            return new PersonMemoryWriteRequest(
                    personId,
                    mappedMessages,
                    metadata,
                    infer == null || infer
            );
        }
    }

    public record MessageRequest(String role, String content) {
        MemoryMessage toDomain() {
            String normalizedRole = requiredText(role, "role").toUpperCase(Locale.ROOT);
            MemoryMessageRole parsedRole;
            try {
                parsedRole = MemoryMessageRole.valueOf(normalizedRole);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "role must be one of USER, ASSISTANT or SYSTEM"
                );
            }
            return new MemoryMessage(parsedRole, content);
        }
    }

    public record SearchRequest(
            String query,
            Set<String> sections,
            Integer maxItems
    ) {
        PersonMemoryQuery toDomain(PersonId personId) {
            Set<MemorySection> mappedSections = sections == null
                    ? Set.of()
                    : sections.stream().map(SearchRequest::parseSection).collect(
                            java.util.stream.Collectors.toUnmodifiableSet()
                    );
            int requestedMaxItems = maxItems == null ? 5 : maxItems;
            return new PersonMemoryQuery(
                    personId,
                    query,
                    mappedSections,
                    requestedMaxItems
            );
        }

        private static MemorySection parseSection(String section) {
            String normalized = requiredText(section, "section").toUpperCase(Locale.ROOT);
            try {
                return MemorySection.valueOf(normalized);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("unknown memory section: " + section);
            }
        }
    }

    public record WriteResponse(
            String personId,
            List<MutationResponse> mutations
    ) {
    }

    public record MutationResponse(
            String memoryId,
            String content,
            String event
    ) {
        static MutationResponse from(MemoryMutation mutation) {
            return new MutationResponse(
                    mutation.memoryId(),
                    mutation.content(),
                    mutation.event()
            );
        }
    }

    public record SearchResponse(
            String personId,
            String availability,
            List<MemoryResponse> memories
    ) {
        static SearchResponse from(PersonId personId, PersonMemoryContext context) {
            return new SearchResponse(
                    personId.toString(),
                    context.availability().name(),
                    context.items().stream().map(MemoryResponse::from).toList()
            );
        }
    }

    public record MemoryResponse(
            String memoryId,
            String section,
            String content,
            double relevance,
            Instant createdAt,
            Instant updatedAt
    ) {
        static MemoryResponse from(MemoryItem item) {
            return new MemoryResponse(
                    item.id(),
                    item.section().name(),
                    item.content(),
                    item.relevance(),
                    item.createdAt(),
                    item.updatedAt()
            );
        }
    }
}

final class MemoryTestUnavailableException extends RuntimeException {
    MemoryTestUnavailableException(String message) {
        super(message);
    }
}

final class MemoryTestProviderException extends RuntimeException {
    MemoryTestProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
