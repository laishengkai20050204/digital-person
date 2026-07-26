package com.laishengkai.digitalperson.agent;

import com.laishengkai.digitalperson.dialogue.ModelToolCall;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable lookup of the executable tools advertised for one agent request. */
final class AgentToolRegistry {
    private final Map<String, AgentTool> toolsByName;

    private AgentToolRegistry(Map<String, AgentTool> toolsByName) {
        this.toolsByName = toolsByName;
    }

    static AgentToolRegistry from(List<AgentTool> tools) {
        Map<String, AgentTool> indexed = new HashMap<>();
        for (AgentTool tool : tools) {
            String name = tool.specification().name();
            AgentTool previous = indexed.put(name, tool);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate agent tool name: " + name
                );
            }
        }
        return new AgentToolRegistry(Map.copyOf(indexed));
    }

    int size() {
        return toolsByName.size();
    }

    boolean contains(String name) {
        return toolsByName.containsKey(name);
    }

    AgentTool tool(String name) {
        return toolsByName.get(name);
    }

    boolean allParallelSafe(List<ModelToolCall> calls) {
        return calls.stream()
                .map(call -> tool(call.name()))
                .allMatch(tool -> java.util.Objects.requireNonNull(
                        tool.executionPolicy(),
                        "tool executionPolicy cannot be null"
                ) == AgentToolExecutionPolicy.PARALLEL_SAFE);
    }
}
