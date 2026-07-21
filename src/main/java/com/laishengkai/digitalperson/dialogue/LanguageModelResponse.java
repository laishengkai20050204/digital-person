package com.laishengkai.digitalperson.dialogue;

import java.util.Objects;

/**
 * Provider-neutral text returned by a language model.
 *
 * <p>The response text is intentionally omitted from {@link #toString()} to
 * prevent generated private content from being written to logs accidentally.</p>
 */
public record LanguageModelResponse(String text) {
    public LanguageModelResponse {
        text = Objects.requireNonNull(text, "text cannot be null");
    }

    @Override
    public String toString() {
        return "LanguageModelResponse[textLength=" + text.length() + "]";
    }
}
