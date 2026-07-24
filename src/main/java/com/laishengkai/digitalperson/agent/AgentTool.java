package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;

import java.util.concurrent.CompletionStage;

/** One application-owned tool that can be advertised to and executed for a model. */
public interface AgentTool {

    /** Describes the tool to the model. */
    ModelToolSpecification specification();

    /**
     * Declares whether sibling calls may execute concurrently. Tools are serialized by
     * default so adding a state-changing tool cannot accidentally introduce races.
     */
    default AgentToolExecutionPolicy executionPolicy() {
        return AgentToolExecutionPolicy.SERIAL;
    }

    /**
     * Executes one model-issued JSON argument object.
     *
     * @param argumentsJson raw JSON arguments emitted by the model
     * @return asynchronous text or JSON result to send back as a tool-result message
     */
    CompletionStage<String> execute(String argumentsJson);
}
