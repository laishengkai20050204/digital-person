package com.laishengkai.digitalperson.dialogue;

/**
 * System-owned boundary for one text generation request.
 *
 * <p>Application and domain code depend on this interface instead of depending
 * directly on LangChain4j. Provider-specific implementations belong in the
 * infrastructure layer.</p>
 */
@FunctionalInterface
public interface LanguageModelGateway {

    /**
     * Sends one request to the configured language model.
     *
     * @param request system and user messages for one model invocation
     * @return generated model response
     * @throws LanguageModelException when the provider call fails or returns an
     *                                invalid response
     */
    LanguageModelResponse generate(LanguageModelRequest request);
}
