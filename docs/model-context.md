# Model Context Boundaries

The project uses different message layouts for state evaluation and end-user dialogue.

## State evaluation

A state evaluation is a classification/extraction request, not a continuation of the chat. It sends exactly:

```text
SystemModelMessage(state evaluation rules)
UserModelMessage(single-line serialized StateEvaluationContext JSON)
```

`recentConversation` remains a structured array inside the JSON so historical user text is treated as evidence rather than as a new instruction. The JSON string becomes the OpenAI-compatible request field `messages[1].content` after the LangChain4j adapter maps `UserModelMessage` to `UserMessage`.

The context includes:

```text
personId
identity
personality
currentState
activeEffects
newEvent
activeEvents
recentEvents
memory
recentConversation
evaluationTime
```

`activeEffects` contains semantic snapshots only. Internal effect identifiers and evaluated-event bookkeeping are not exposed. The model must not reproduce or extend an existing active effect; it submits only effects independently introduced by `newEvent`.

## Dialogue generation

Dialogue generation must preserve actual roles. `ConversationMessageAssembler` builds:

```text
SystemModelMessage(person definition and dialogue policy)
UserModelMessage / AssistantModelMessage / SystemModelMessage for prior turns
UserModelMessage(current user message)
```

The caller must exclude the current user message from the supplied history to avoid duplication. The model gateway never loads or persists conversation history by itself.

## Relationship data

Relationship information remains in the memory subsystem under the `RELATIONSHIP` partition. A future provider can make canonical relationship memory mandatory while still retrieving event-relevant relationship episodes through ordinary relevance search.
