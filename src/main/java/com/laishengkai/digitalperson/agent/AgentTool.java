package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;

import java.util.concurrent.CompletionStage;

/** One application-owned tool that can be advertised to and executed for a model. */
public interface AgentTool {

    /** Describes the tool to the model. */
    ModelToolSpecification specification();

    /**
     * Executes one model-issued JSON argument object.
     *
     * @param argumentsJson raw JSON arguments emitted by the model
     * @return asynchronous text or JSON result to send back as a tool-result message
     */
    CompletionStage<String> execute(String argumentsJson);
}
