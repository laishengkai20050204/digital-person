package com.laishengkai.digitalperson.infrastructure.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Runtime policy for automatic dialogue-to-structured-memory extraction. */
@ConfigurationProperties(prefix = "digital-person.memory.extraction")
public record StructuredMemoryExtractionProperties(
        Boolean enabled,
        Integer recentTurnsToKeep,
        Integer batchTurns,
        Integer maximumEntities,
        Integer maximumFacts,
        Double minimumConfidence,
        Double minimumImportance,
        Integer maxOutputTokens,
        Double temperature
) {
    private static final boolean DEFAULT_ENABLED = false;
    private static final int DEFAULT_RECENT_TURNS_TO_KEEP = 2;
    private static final int DEFAULT_BATCH_TURNS = 8;
    private static final int DEFAULT_MAXIMUM_ENTITIES = 8;
    private static final int DEFAULT_MAXIMUM_FACTS = 12;
    private static final double DEFAULT_MINIMUM_CONFIDENCE = 0.70;
    private static final double DEFAULT_MINIMUM_IMPORTANCE = 0.35;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_400;
    private static final double DEFAULT_TEMPERATURE = 0.1;

    @ConstructorBinding
    public StructuredMemoryExtractionProperties(
            Boolean enabled,
            Integer recentTurnsToKeep,
            Integer batchTurns,
            Integer maximumEntities,
            Integer maximumFacts,
            Double minimumConfidence,
            Double minimumImportance,
            Integer maxOutputTokens,
            Double temperature
    ) {
        this.enabled = enabled == null ? DEFAULT_ENABLED : enabled;
        this.recentTurnsToKeep = positiveOrDefault(
                recentTurnsToKeep,
                DEFAULT_RECENT_TURNS_TO_KEEP,
                "recentTurnsToKeep"
        );
        this.batchTurns = positiveOrDefault(
                batchTurns,
                DEFAULT_BATCH_TURNS,
                "batchTurns"
        );
        this.maximumEntities = positiveOrDefault(
                maximumEntities,
                DEFAULT_MAXIMUM_ENTITIES,
                "maximumEntities"
        );
        this.maximumFacts = positiveOrDefault(
                maximumFacts,
                DEFAULT_MAXIMUM_FACTS,
                "maximumFacts"
        );
        this.minimumConfidence = unitIntervalOrDefault(
                minimumConfidence,
                DEFAULT_MINIMUM_CONFIDENCE,
                "minimumConfidence"
        );
        this.minimumImportance = unitIntervalOrDefault(
                minimumImportance,
                DEFAULT_MINIMUM_IMPORTANCE,
                "minimumImportance"
        );
        this.maxOutputTokens = positiveOrDefault(
                maxOutputTokens,
                DEFAULT_MAX_OUTPUT_TOKENS,
                "maxOutputTokens"
        );
        this.temperature = temperatureOrDefault(
                temperature,
                DEFAULT_TEMPERATURE,
                "temperature"
        );
    }

    private static int positiveOrDefault(Integer value, int defaultValue, String fieldName) {
        int normalized = value == null ? defaultValue : value;
        if (normalized <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return normalized;
    }

    private static double unitIntervalOrDefault(
            Double value,
            double defaultValue,
            String fieldName
    ) {
        double normalized = value == null ? defaultValue : value;
        if (!Double.isFinite(normalized) || normalized < 0.0 || normalized > 1.0) {
            throw new IllegalArgumentException(
                    fieldName + " must be between 0.0 and 1.0"
            );
        }
        return normalized;
    }

    private static double temperatureOrDefault(
            Double value,
            double defaultValue,
            String fieldName
    ) {
        double normalized = value == null ? defaultValue : value;
        if (!Double.isFinite(normalized) || normalized < 0.0 || normalized > 2.0) {
            throw new IllegalArgumentException(
                    fieldName + " must be between 0.0 and 2.0"
            );
        }
        return normalized;
    }
}
