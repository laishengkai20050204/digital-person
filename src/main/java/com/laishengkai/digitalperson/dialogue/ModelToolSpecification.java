package com.laishengkai.digitalperson.dialogue;

import java.util.Objects;

/**
 * Describes one function-style tool visible during a single model invocation.
 *
 * @param parametersJsonSchema JSON Schema object describing tool arguments
 */
public record ModelToolSpecification(
        String name,
        String description,
        String parametersJsonSchema
) {
    public ModelToolSpecification {
        name = requireText(name, "name");
        description = requireText(description, "description");
        parametersJsonSchema = requireText(
                parametersJsonSchema,
                "parametersJsonSchema"
        );
        if (!parametersJsonSchema.startsWith("{")) {
            throw new IllegalArgumentException(
                    "parametersJsonSchema must be a JSON object"
            );
        }
    }

    @Override
    public String toString() {
        return "ModelToolSpecification[name="
                + name
                + ", descriptionLength="
                + description.length()
                + ", schemaLength="
                + parametersJsonSchema.length()
                + "]";
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }
}
