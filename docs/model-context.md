# Model Context Boundaries

The project uses different message layouts for state evaluation and end-user dialogue.

## State evaluation

A state evaluation is a classification/extraction request, not a continuation of the chat. It sends exactly:

```text
SystemModelMessage(state evaluation rules)
UserModelMessage(single-line serialized StateEvaluationContext JSON)
```

`recentConversation` remains a structured array inside the JSON so historical user text is treated as evidence rather than as a new instruction. The JSON string becomes the OpenAI-compatible request field `messages[1].content` after the LangChain4j adapter maps `UserModelMessage` to `UserMessage`.

The serialized string is transient request data: the model gateway neither persists it nor treats it as conversation history.

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
temporal
evaluationTime
```

`activeEffects` contains semantic snapshots only. Internal effect identifiers and evaluated-event bookkeeping are not exposed. The model must not reproduce or extend an existing active effect; it submits only effects independently introduced by `newEvent`.

`temporal` is calculated in Java from `evaluationTime` and the identity timezone. It includes the current UTC instant, timezone, local offset datetime, day of week, local hour, and weekend flag. Event snapshots retain raw `startTime`/`endTime` and add model-friendly derived timing:

```text
active
elapsedMinutes
minutesSinceEnd
```

For active events, `elapsedMinutes` is the duration from start until evaluation. For completed events it is the actual event duration, while `minutesSinceEnd` reports how long ago the event ended.

## Autonomous activity decisions

Activity selection is a separate classification/planning request. It sends:

```text
SystemModelMessage(activity lifecycle decision rules)
UserModelMessage(single-line serialized PersonActivityDecisionContext JSON)
```

The model must call `submit_event_lifecycle_plan` exactly once. The tool is a pure result-submission boundary; Java validates and atomically applies the complete plan. It does not expose direct side-effecting `start_event` or `finish_event` tools.

The activity context contains the settled current state, active effects, person/user events, identity, personality, memory, recent conversation, an optional external observation, rich local-time context, and evaluation time. Only events whose snapshot owner is `PERSON` can be changed.

Both state evaluation and activity decision contexts are projections of one shared `PersonModelContextAssembler`. The shared assembler owns identity, personality, state, active-effect filtering, event deduplication and ordering, 24-hour recent-event selection, derived event timing, temporal conversion, memory retrieval, and recent-conversation retrieval. Specialized assemblers add only `newEvent` or `observation` and their task-specific retrieval query.

Newly started events are then passed to the existing state-effect evaluator. This preserves the causal order:

```text
state and context -> choose activity -> create event -> evaluate event effects
```

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
