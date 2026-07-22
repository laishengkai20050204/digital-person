package com.laishengkai.digitalperson.agent;

import java.util.concurrent.CompletionStage;

/** Executes a complete model/tool loop until a final assistant response is produced. */
@FunctionalInterface
public interface AgentExecutor {

    /**
     * Executes one agent request without blocking the caller thread.
     *
     * @param request immutable messages, options, executable tools and safety limit
     * @return final response and the complete message trace produced by this run
     */
    CompletionStage<AgentResult> execute(AgentRequest request);
}
