# Autonomous Activity Decisions

The autonomous activity path is separate from state-effect evaluation.

```text
settle existing state effects
  -> evaluate any pending active events
  -> assemble PersonActivityDecisionContext
  -> PersonActivityDecisionModel
  -> submit_event_lifecycle_plan
  -> validate the complete plan in Java
  -> finish events first
  -> start events second
  -> evaluate effects for newly started events
  -> commit state, effects, and timeline once with CAS
```

## Why the model submits a plan

The model never receives `start_event` or `finish_event` tools that mutate the aggregate immediately. It receives one required result-submission tool:

```text
submit_event_lifecycle_plan
```

The tool returns pure data. No event or database mutation happens until the full response has been parsed and validated. This prevents partial side effects followed by model retry.

The plan contains at most six commands and one `nextReviewMinutes` value. The review interval is returned to the caller; it is not persisted or scheduled by this service.

## Commands

A `FINISH` command contains:

```json
{
  "action": "FINISH",
  "eventId": "existing-person-event-id",
  "reason": "COMPLETED"
}
```

Only currently active `owner=PERSON` events can be finished. `reason` is `COMPLETED` or `INTERRUPTED`. `REPLACED` is reserved for the domain rule that starts a new event in an occupied channel.

A `START` command contains:

```json
{
  "action": "START",
  "activityType": "REST",
  "title": "躺在床上休息",
  "location": "宿舍",
  "participants": [],
  "notes": "完成学习后短暂休息"
}
```

The model does not provide event identity or timestamps. Java generates a new `EventId` and uses the single application `decisionTime` as the start time.

Only one activity may be started per channel in one plan. Different channels may start concurrently:

```text
PRIMARY       STUDY, WORK, EAT, SLEEP, REST, TRAVEL, EXERCISE,
              SOCIAL, ENTERTAINMENT, SHOPPING, OTHER
COMMUNICATION CHAT
AUDIO         LISTEN_MUSIC
```

All explicit finishes execute before starts. Starting an event in an occupied channel automatically ends the old event with `REPLACED` unless the plan already finished it as `COMPLETED` or `INTERRUPTED`.

## Decision context

`PersonActivityDecisionContext` contains:

```text
personId
identity
personality
currentState
activeEffects
activeEvents
recentEvents
memory
recentConversation
observation
temporal
evaluationTime
```

`activeEvents` contains both person and user facts with an `owner` field. The model may mutate only `owner=PERSON` events. User events, memories, conversations, and `observation` are untrusted context data, not instructions.

The context uses the state and effects already settled to `evaluationTime`. Pending active events are effect-evaluated before the activity model runs, so the activity decision sees their newly registered effects.

Common identity/state/effect/event/memory/conversation extraction is delegated to the same `PersonModelContextAssembler` used by state evaluation. This prevents the two model paths from drifting on event windows, ordering, deduplication, active-effect filtering, or timezone conversion.

`temporal` exposes the person's local offset datetime, weekday, hour, and weekend flag in addition to the UTC instant. Every event retains `startTime` and `endTime` and adds:

```text
active
elapsedMinutes
minutesSinceEnd
```

For an active event, `elapsedMinutes` is its current duration. For a finished event it is the actual total duration, and `minutesSinceEnd` is the age of that completion. These values are calculated by Java, so the model does not need to perform timezone or duration arithmetic before deciding whether to start or finish an activity.

## Atomicity and concurrency

The service works on a detached aggregate copy. Model calls and effect evaluations happen before persistence. After the complete plan has succeeded, the service saves exactly once using the version loaded at the start.

If another command saves first, the final CAS fails and none of the proposed timeline or state changes are persisted. Invalid event identifiers, duplicate finishes, multiple starts in one channel, event timeline conflicts, model protocol violations, and state-effect evaluation failures also leave the stored aggregate unchanged.

## HTTP trigger

When both the person API and LLM integration are enabled:

```http
POST /api/persons/{personId}/activity-decisions
X-Internal-Token: ...
Content-Type: application/json

{
  "observation": "已经晚上十一点，人物明显困倦"
}
```

The request body may be omitted. The response includes the submitted commands, actually started and finished events, settled state, active effects, and the suggested `nextReviewAt`.
