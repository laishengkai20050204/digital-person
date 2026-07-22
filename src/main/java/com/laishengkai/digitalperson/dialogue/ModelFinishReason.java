package com.laishengkai.digitalperson.dialogue;

/** Provider-neutral reason that a single model invocation ended. */
public enum ModelFinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    OTHER,
    UNKNOWN
}
