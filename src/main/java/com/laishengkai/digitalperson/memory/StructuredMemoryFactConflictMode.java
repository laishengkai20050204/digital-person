package com.laishengkai.digitalperson.memory;

/** Conservative policy for handling an extracted fact that occupies an existing fact slot. */
public enum StructuredMemoryFactConflictMode {
    KEEP_EXISTING,
    SUPERSEDE_EXISTING
}
