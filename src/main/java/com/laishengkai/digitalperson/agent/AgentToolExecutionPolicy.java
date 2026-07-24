package com.laishengkai.digitalperson.agent;

/** Declares whether a tool is safe to execute concurrently with sibling tool calls. */
public enum AgentToolExecutionPolicy {
    /** Default for tools with side effects or unknown concurrency semantics. */
    SERIAL,

    /** Explicit opt-in for read-only or otherwise concurrency-safe tools. */
    PARALLEL_SAFE
}
