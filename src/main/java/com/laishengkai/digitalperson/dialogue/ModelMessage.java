package com.laishengkai.digitalperson.dialogue;

/**
 * One message in the provider-neutral model conversation.
 *
 * <p>Tool requests are part of {@link AssistantModelMessage}, because they are
 * emitted by the assistant role. Tool results are separate messages returned by
 * the application after executing those requests.</p>
 */
public sealed interface ModelMessage
        permits SystemModelMessage,
                UserModelMessage,
                AssistantModelMessage,
                ToolResultModelMessage {
}
