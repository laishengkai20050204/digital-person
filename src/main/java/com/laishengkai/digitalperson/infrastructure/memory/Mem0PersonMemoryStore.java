package com.laishengkai.digitalperson.infrastructure.memory;

import com.laishengkai.digitalperson.memory.MemoryMessage;
import com.laishengkai.digitalperson.memory.MemoryMessageRole;
import com.laishengkai.digitalperson.memory.MemoryMutation;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.memory.PersonMemoryStore;
import com.laishengkai.digitalperson.memory.PersonMemoryWriteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/** Mem0-backed write adapter used by dialogue orchestration and maintenance flows. */
public final class Mem0PersonMemoryStore implements PersonMemoryStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(Mem0PersonMemoryStore.class);
    private static final List<String> CORRECTION_MARKERS = List.of(
            "更正", "纠正", "之前说错", "其实不是", "不再", "已经不",
            "改成", "改为", "换成", "取消", "停止", "放弃", "相反"
    );

    private final Mem0HttpClient client;
    private final boolean deduplicationEnabled;
    private final double duplicateSemanticThreshold;
    private final double duplicateTextThreshold;
    private final int duplicateMaxCandidates;

    /** Compatibility constructor used by focused adapter tests without deduplication. */
    Mem0PersonMemoryStore(Mem0HttpClient client) {
        this(
                client,
                false,
                Mem0Properties.DEFAULT_DUPLICATE_SEMANTIC_THRESHOLD,
                Mem0Properties.DEFAULT_DUPLICATE_TEXT_THRESHOLD,
                Mem0Properties.DEFAULT_DUPLICATE_MAX_CANDIDATES
        );
    }

    Mem0PersonMemoryStore(Mem0HttpClient client, Mem0Properties properties) {
        this(
                client,
                properties.deduplicationEnabled(),
                properties.duplicateSemanticThreshold(),
                properties.duplicateTextThreshold(),
                properties.duplicateMaxCandidates()
        );
    }

    Mem0PersonMemoryStore(
            Mem0HttpClient client,
            boolean deduplicationEnabled,
            double duplicateSemanticThreshold,
            double duplicateTextThreshold,
            int duplicateMaxCandidates
    ) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.deduplicationEnabled = deduplicationEnabled;
        this.duplicateSemanticThreshold = probability(
                duplicateSemanticThreshold,
                "duplicateSemanticThreshold"
        );
        this.duplicateTextThreshold = probability(
                duplicateTextThreshold,
                "duplicateTextThreshold"
        );
        if (duplicateMaxCandidates <= 0) {
            throw new IllegalArgumentException("duplicateMaxCandidates must be positive");
        }
        this.duplicateMaxCandidates = duplicateMaxCandidates;
    }

    @Override
    public CompletionStage<List<MemoryMutation>> add(PersonMemoryWriteRequest request) {
        PersonMemoryWriteRequest writeRequest = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        if (!shouldCheckForDuplicate(writeRequest)) {
            return addWithoutDuplicateCheck(writeRequest);
        }

        String queryText = userMessageText(writeRequest);
        if (queryText.isBlank() || looksLikeCorrection(queryText)) {
            return addWithoutDuplicateCheck(writeRequest);
        }

        PersonMemoryQuery query = new PersonMemoryQuery(
                writeRequest.personId(),
                queryText,
                Set.of(),
                duplicateMaxCandidates
        );
        return client.search(query, duplicateSemanticThreshold)
                .handle((response, failure) -> failure == null
                        ? findDuplicate(response, writeRequest, queryText)
                        : DuplicateCheck.failed(failure))
                .thenCompose(check -> {
                    if (check.failure() != null) {
                        LOGGER.warn(
                                "Mem0 duplicate check failed; proceeding with recording: personId={}",
                                writeRequest.personId(),
                                check.failure()
                        );
                        return addWithoutDuplicateCheck(writeRequest);
                    }
                    if (check.duplicate()) {
                        LOGGER.info(
                                "Mem0 dialogue recording suppressed as a semantic duplicate: personId={}, existingMemoryId={}, semanticScore={}, textSimilarity={}",
                                writeRequest.personId(),
                                check.memoryId(),
                                check.semanticScore(),
                                check.textSimilarity()
                        );
                        return CompletableFuture.completedFuture(List.<MemoryMutation>of());
                    }
                    return addWithoutDuplicateCheck(writeRequest);
                });
    }

    @Override
    public CompletionStage<Void> delete(String memoryId) {
        return client.delete(memoryId);
    }

    private CompletionStage<List<MemoryMutation>> addWithoutDuplicateCheck(
            PersonMemoryWriteRequest request
    ) {
        return client.add(request).thenApply(Mem0PersonMemoryStore::parseMutations);
    }

    private boolean shouldCheckForDuplicate(PersonMemoryWriteRequest request) {
        return deduplicationEnabled
                && request.infer()
                && "dialogue".equalsIgnoreCase(request.metadata().getOrDefault("source", ""));
    }

    private DuplicateCheck findDuplicate(
            JsonNode response,
            PersonMemoryWriteRequest request,
            String queryText
    ) {
        JsonNode results = response != null && response.has("results")
                ? response.get("results")
                : response;
        if (results == null || !results.isArray()) {
            return DuplicateCheck.none();
        }

        String requestSection = request.metadata().getOrDefault("section", "").strip();
        for (JsonNode result : results) {
            double semanticScore = score(result);
            if (semanticScore < duplicateSemanticThreshold) {
                continue;
            }
            JsonNode metadata = result.path("metadata");
            String source = text(metadata, "source");
            if (!source.isBlank() && !"dialogue".equalsIgnoreCase(source)) {
                continue;
            }
            String attributedTo = text(result, "attributed_to");
            if (!attributedTo.isBlank() && !"user".equalsIgnoreCase(attributedTo)) {
                continue;
            }
            String candidateSection = text(metadata, "section");
            if (!requestSection.isBlank()
                    && !candidateSection.isBlank()
                    && !requestSection.equalsIgnoreCase(candidateSection)) {
                continue;
            }
            String memory = firstText(result, "memory", "text", "data");
            String memoryId = firstText(result, "id", "memory_id");
            if (memory.isBlank() || memoryId.isBlank()) {
                continue;
            }
            double textSimilarity = textSimilarity(queryText, memory);
            if (textSimilarity >= duplicateTextThreshold) {
                return DuplicateCheck.duplicate(
                        memoryId,
                        semanticScore,
                        textSimilarity
                );
            }
        }
        return DuplicateCheck.none();
    }

    private static String userMessageText(PersonMemoryWriteRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == MemoryMessageRole.USER)
                .map(MemoryMessage::content)
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private static boolean looksLikeCorrection(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return CORRECTION_MARKERS.stream().anyMatch(normalized::contains);
    }

    static double textSimilarity(String left, String right) {
        int[] leftCodePoints = normalizedCodePoints(left);
        int[] rightCodePoints = normalizedCodePoints(right);
        if (leftCodePoints.length == 0 || rightCodePoints.length == 0) {
            return 0.0;
        }
        if (leftCodePoints.length == 1 || rightCodePoints.length == 1) {
            return leftCodePoints.length == rightCodePoints.length
                    && leftCodePoints[0] == rightCodePoints[0] ? 1.0 : 0.0;
        }

        Map<Long, Integer> leftBigrams = bigramCounts(leftCodePoints);
        Map<Long, Integer> rightBigrams = bigramCounts(rightCodePoints);
        int intersection = 0;
        for (Map.Entry<Long, Integer> entry : leftBigrams.entrySet()) {
            intersection += Math.min(
                    entry.getValue(),
                    rightBigrams.getOrDefault(entry.getKey(), 0)
            );
        }
        int leftCount = leftCodePoints.length - 1;
        int rightCount = rightCodePoints.length - 1;
        return (2.0 * intersection) / (leftCount + rightCount);
    }

    private static int[] normalizedCodePoints(String value) {
        return Objects.requireNonNullElse(value, "")
                .strip()
                .toLowerCase(Locale.ROOT)
                .codePoints()
                .filter(Character::isLetterOrDigit)
                .toArray();
    }

    private static Map<Long, Integer> bigramCounts(int[] codePoints) {
        Map<Long, Integer> counts = new HashMap<>();
        for (int index = 0; index < codePoints.length - 1; index++) {
            long key = ((long) codePoints[index] << 32)
                    ^ (codePoints[index + 1] & 0xffffffffL);
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static List<MemoryMutation> parseMutations(JsonNode response) {
        JsonNode results = response != null && response.has("results")
                ? response.get("results")
                : response;
        if (results == null || !results.isArray()) {
            return List.of();
        }
        List<MemoryMutation> mutations = new ArrayList<>();
        for (JsonNode result : results) {
            String id = text(result, "id");
            String event = text(result, "event");
            if (id.isBlank() || event.isBlank()) {
                continue;
            }
            mutations.add(new MemoryMutation(
                    id,
                    text(result, "memory"),
                    event
            ));
        }
        return List.copyOf(mutations);
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = text(node, fieldName);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value != null && value.isTextual() ? value.asString().strip() : "";
    }

    private static double score(JsonNode node) {
        JsonNode value = node.get("score");
        double normalized = value != null && value.isNumber() ? value.asDouble() : 0.0;
        if (!Double.isFinite(normalized)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, normalized));
    }

    private static double probability(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(fieldName + " must be between 0.0 and 1.0");
        }
        return value;
    }

    private record DuplicateCheck(
            boolean duplicate,
            String memoryId,
            double semanticScore,
            double textSimilarity,
            Throwable failure
    ) {
        private static DuplicateCheck none() {
            return new DuplicateCheck(false, "", 0.0, 0.0, null);
        }

        private static DuplicateCheck duplicate(
                String memoryId,
                double semanticScore,
                double textSimilarity
        ) {
            return new DuplicateCheck(
                    true,
                    memoryId,
                    semanticScore,
                    textSimilarity,
                    null
            );
        }

        private static DuplicateCheck failed(Throwable failure) {
            return new DuplicateCheck(
                    false,
                    "",
                    0.0,
                    0.0,
                    Objects.requireNonNull(failure, "failure cannot be null")
            );
        }
    }
}
