package com.laishengkai.digitalperson.dialogue;

import java.util.concurrent.CompletionStage;

/**
 * System-owned boundary for one language-model invocation.
 *
 * <p>Application and domain code depend on this interface instead of depending
 * directly on LangChain4j. Each invocation represents exactly one provider
 * request; tool execution loops belong in a higher-level agent executor.</p>
 */
@FunctionalInterface
public interface LanguageModelGateway {

    /**
     * Sends one immutable request to the configured language model.
     *
     * @param request complete message history, request options and visible tools
     * @return asynchronous provider response
     */
    CompletionStage<LanguageModelResponse> invoke(LanguageModelRequest request);
}
