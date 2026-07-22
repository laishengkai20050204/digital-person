package com.laishengkai.digitalperson.dialogue;

import java.util.Objects;

/** Provider-neutral response format requested for one model invocation. */
public record ModelResponseFormat(
        Type type,
        String schemaName,
        String jsonSchema
) {
    public enum Type {
        TEXT,
        JSON_OBJECT,
        JSON_SCHEMA
    }

    public ModelResponseFormat {
        type = Objects.requireNonNull(type, "type cannot be null");
        schemaName = normalize(schemaName);
        jsonSchema = normalize(jsonSchema);

        if (type == Type.JSON_SCHEMA) {
            schemaName = requireText(schemaName, "schemaName");
            jsonSchema = requireText(jsonSchema, "jsonSchema");
            if (!jsonSchema.startsWith("{")) {
                throw new IllegalArgumentException("jsonSchema must be a JSON object");
            }
        } else if (!schemaName.isEmpty() || !jsonSchema.isEmpty()) {
            throw new IllegalArgumentException(
                    "schemaName and jsonSchema are only valid for JSON_SCHEMA"
            );
        }
    }

    public static ModelResponseFormat text() {
        return new ModelResponseFormat(Type.TEXT, "", "");
    }

    public static ModelResponseFormat jsonObject() {
        return new ModelResponseFormat(Type.JSON_OBJECT, "", "");
    }

    public static ModelResponseFormat jsonSchema(
            String schemaName,
            String jsonSchema
    ) {
        return new ModelResponseFormat(Type.JSON_SCHEMA, schemaName, jsonSchema);
    }

    @Override
    public String toString() {
        return "ModelResponseFormat[type="
                + type
                + ", schemaName="
                + schemaName
                + ", schemaLength="
                + jsonSchema.length()
                + "]";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
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
