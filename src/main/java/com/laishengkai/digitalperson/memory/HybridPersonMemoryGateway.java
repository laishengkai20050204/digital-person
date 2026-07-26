package com.laishengkai.digitalperson.memory;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Combines structured and semantic retrieval without exposing provider details. */
public final class HybridPersonMemoryGateway implements PersonMemoryGateway {
    private static final Comparator<MemoryItem> ITEM_ORDER = Comparator
            .comparingDouble(MemoryItem::relevance)
            .reversed()
            .thenComparing(
                    MemoryItem::updatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())
            )
            .thenComparing(
                    MemoryItem::createdAt,
                    Comparator.nullsLast(Comparator.reverseOrder())
            )
            .thenComparing(MemoryItem::id);

    private final StructuredMemorySource structuredSource;
    private final SemanticMemorySource semanticSource;

    public HybridPersonMemoryGateway(
            StructuredMemorySource structuredSource,
            SemanticMemorySource semanticSource
    ) {
        this.structuredSource = Objects.requireNonNull(
                structuredSource,
                "structuredSource cannot be null"
        );
        this.semanticSource = Objects.requireNonNull(
                semanticSource,
                "semanticSource cannot be null"
        );
    }

    @Override
    public CompletionStage<PersonMemoryContext> retrieve(PersonMemoryQuery query) {
        PersonMemoryQuery request = Objects.requireNonNull(
                query,
                "query cannot be null"
        );
        CompletionStage<PersonMemoryContext> structured = safeRetrieve(
                structuredSource.retrieve(request)
        );
        CompletionStage<PersonMemoryContext> semantic = safeRetrieve(
                semanticSource.retrieve(request)
        );
        return structured.thenCombine(
                semantic,
                (left, right) -> merge(request.maxItems(), left, right)
        );
    }

    private static CompletionStage<PersonMemoryContext> safeRetrieve(
            CompletionStage<PersonMemoryContext> stage
    ) {
        if (stage == null) {
            return CompletableFuture.completedFuture(PersonMemoryContext.unavailable());
        }
        return stage.exceptionally(ignored -> PersonMemoryContext.unavailable());
    }

    static PersonMemoryContext merge(
            int maxItems,
            PersonMemoryContext structured,
            PersonMemoryContext semantic
    ) {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive");
        }
        PersonMemoryContext left = Objects.requireNonNull(
                structured,
                "structured cannot be null"
        );
        PersonMemoryContext right = Objects.requireNonNull(
                semantic,
                "semantic cannot be null"
        );

        List<MemoryItem> combined = new ArrayList<>(left.items().size() + right.items().size());
        combined.addAll(left.items());
        combined.addAll(right.items());
        combined.sort(ITEM_ORDER);

        Map<String, MemoryItem> unique = new LinkedHashMap<>();
        for (MemoryItem item : combined) {
            unique.putIfAbsent(deduplicationKey(item), item);
            if (unique.size() >= maxItems) {
                break;
            }
        }
        MemoryAvailability availability = availability(
                left.availability(),
                right.availability()
        );
        return new PersonMemoryContext(availability, List.copyOf(unique.values()));
    }

    private static MemoryAvailability availability(
            MemoryAvailability left,
            MemoryAvailability right
    ) {
        if (left == MemoryAvailability.AVAILABLE
                || right == MemoryAvailability.AVAILABLE) {
            return MemoryAvailability.AVAILABLE;
        }
        if (left == MemoryAvailability.DISABLED
                && right == MemoryAvailability.DISABLED) {
            return MemoryAvailability.DISABLED;
        }
        return MemoryAvailability.UNAVAILABLE;
    }

    private static String deduplicationKey(MemoryItem item) {
        String normalized = Normalizer.normalize(
                        item.content(),
                        Normalizer.Form.NFKC
                )
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return item.section().name() + '\u0000' + normalized;
    }
}
