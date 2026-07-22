package com.laishengkai.digitalperson.dialogue;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, provider-neutral input for exactly one language-model invocation.
 *
 * <p>The caller supplies the complete context for this invocation. The gateway
 * does not own or load conversation history.</p>
 */
public record LanguageModelRequest(
        List<ModelMessage> messages,
        ModelInvocationOptions options,
        List<ModelToolSpecification> tools
) {
    public LanguageModelRequest {
        messages = List.copyOf(Objects.requireNonNull(
                messages,
                "messages cannot be null"
        ));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be empty");
        }
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("messages cannot contain null");
        }

        options = Objects.requireNonNullElseGet(
                options,
                ModelInvocationOptions::defaults
        );
        tools = List.copyOf(Objects.requireNonNullElse(tools, List.of()));
        if (tools.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("tools cannot contain null");
        }
        if (options.toolChoice() == ModelToolChoice.REQUIRED && tools.isEmpty()) {
            throw new IllegalArgumentException(
                    "toolChoice REQUIRED requires at least one tool"
            );
        }
    }

    public static LanguageModelRequest text(
            String systemMessage,
            String userMessage
    ) {
        return new LanguageModelRequest(
                List.of(
                        new SystemModelMessage(systemMessage),
                        new UserModelMessage(userMessage)
                ),
                ModelInvocationOptions.defaults(),
                List.of()
        );
    }

    public static LanguageModelRequest userMessage(String userMessage) {
        return new LanguageModelRequest(
                List.of(new UserModelMessage(userMessage)),
                ModelInvocationOptions.defaults(),
                List.of()
        );
    }

    @Override
    public String toString() {
        return "LanguageModelRequest[messageCount="
                + messages.size()
                + ", toolCount="
                + tools.size()
                + ", options="
                + options
                + "]";
    }
}
