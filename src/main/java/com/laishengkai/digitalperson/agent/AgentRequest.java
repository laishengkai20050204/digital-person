package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.LanguageModelRequest;
import com.laishengkai.digitalperson.dialogue.ModelInvocationOptions;
import com.laishengkai.digitalperson.dialogue.ModelMessage;
import com.laishengkai.digitalperson.dialogue.ModelToolChoice;
import com.laishengkai.digitalperson.dialogue.ModelToolSpecification;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable input for one bounded model/tool execution loop. */
public record AgentRequest(
        List<ModelMessage> messages,
        ModelInvocationOptions options,
        List<AgentTool> tools,
        int maxModelInvocations
) {
    public static final int DEFAULT_MAX_MODEL_INVOCATIONS = 8;
    public static final int MAX_TOOLS = 32;

    public AgentRequest {
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
        if (tools.size() > MAX_TOOLS) {
            throw new IllegalArgumentException(
                    "tools cannot exceed " + MAX_TOOLS
            );
        }
        if (maxModelInvocations <= 0) {
            throw new IllegalArgumentException(
                    "maxModelInvocations must be positive"
            );
        }

        Set<String> names = new HashSet<>();
        for (AgentTool tool : tools) {
            ModelToolSpecification specification = Objects.requireNonNull(
                    tool.specification(),
                    "tool specification cannot be null"
            );
            if (!names.add(specification.name())) {
                throw new IllegalArgumentException(
                        "duplicate agent tool name: " + specification.name()
                );
            }
        }
        if (options.toolChoice() == ModelToolChoice.REQUIRED && tools.isEmpty()) {
            throw new IllegalArgumentException(
                    "toolChoice REQUIRED requires at least one executable tool"
            );
        }
    }

    public static AgentRequest of(
            List<ModelMessage> messages,
            List<AgentTool> tools
    ) {
        return new AgentRequest(
                messages,
                ModelInvocationOptions.defaults(),
                tools,
                DEFAULT_MAX_MODEL_INVOCATIONS
        );
    }

    LanguageModelRequest toModelRequest(List<ModelMessage> currentMessages) {
        List<ModelToolSpecification> specifications = tools.stream()
                .map(AgentTool::specification)
                .toList();
        return new LanguageModelRequest(
                currentMessages,
                options,
                specifications
        );
    }

    @Override
    public String toString() {
        return "AgentRequest[messageCount="
                + messages.size()
                + ", toolCount="
                + tools.size()
                + ", maxModelInvocations="
                + maxModelInvocations
                + ", options="
                + options
                + "]";
    }
}
