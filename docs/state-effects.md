# Unified State Effects

The state system separates facts from their consequences:

- an `Event` records what happened and participates in activity-channel concurrency;
- a `StateEffect` records how one cause is currently changing the person;
- `PersonState` is settled from every effect active during each exact time interval.

Activity channels never own or overwrite state effects.

## Effect shape

Each persisted effect contains:

```text
effectId
sourceEventId (optional)
type
cause
startsAt
endPolicy
fixedEndsAt (when required)
transitions
```

`cause` is a concise factual description of the direct reason or trigger for the effect. It is not chain-of-thought, advice, or generated dialogue.

Each transition `shape` is an hourly signed exponential rate. Its absolute
value is limited to `3.0`; this already moves a value about 95% of the remaining
distance toward its bound in one hour. When several active effects target the
same dimension, their signed rates are summed and the merged rate is saturated
to `[-3.0, 3.0]` before settlement. Older persisted values outside this range
are bounded while loading and are normalized on the next save.

Example:

```json
{
  "type": "EMOTIONAL",
  "cause": "恋人明确提出分手，引发关系丧失感",
  "transitions": [
    {"dimension": "VALENCE", "shape": -0.8},
    {"dimension": "TENSION", "shape": 0.7}
  ],
  "endPolicy": "FIXED_TIME",
  "durationMinutes": 360
}
```

There are no title or keyword rules for breakup, conflict, study, sleep, or other events. The model selects effects from the supplied event, structured identity, current state, existing active effects, HEXACO personality, relationship memory, and recent conversation.

## Effect types

Effects are split when they affect different semantic groups:

```text
EMOTIONAL  -> VALENCE, ENERGY, TENSION
COGNITIVE  -> FOCUS, MENTAL_LOAD, MOTIVATION
PHYSICAL   -> FATIGUE, SLEEPINESS, HUNGER
SOCIAL     -> LONELINESS, SOCIAL_NEED
```

This permits one event to register several effects with different causes and lifecycles.

## End policies

### `EVENT_END`

Requires a source event. The effect stops exactly when that event ends or is replaced.

Examples: attention occupied by an ongoing conversation, hunger falling while eating, relaxation while music is playing.

### `FIXED_TIME`

Stops at `fixedEndsAt` and does not end merely because its source event finished. A source event is optional at the domain level.

Examples: emotional distress after an argument, post-exercise fatigue, caffeine stimulation, accumulated sleep deprivation.

### `EVENT_END_OR_FIXED_TIME`

Requires a source event and a fixed deadline. The earlier boundary wins.

Examples: an environmental or conversational effect that lasts only while the event continues, with a bounded safety limit.

## Event evaluation protocol

Production model evaluation must call `submit_state_effects` exactly once:

```json
{
  "effects": [
    {
      "type": "COGNITIVE",
      "cause": "激烈沟通持续占用注意力",
      "transitions": [
        {"dimension": "MENTAL_LOAD", "shape": 0.5}
      ],
      "endPolicy": "EVENT_END",
      "durationMinutes": 0
    },
    {
      "type": "EMOTIONAL",
      "cause": "关系破裂消息引发持续低落",
      "transitions": [
        {"dimension": "VALENCE", "shape": -0.9}
      ],
      "endPolicy": "FIXED_TIME",
      "durationMinutes": 360
    }
  ]
}
```

A valid no-effect evaluation returns:

```json
{"effects": []}
```

An evaluated-event marker is stored separately, so a valid empty result is not mistaken for an event that was never evaluated.

## Settlement

At each command boundary, the updater:

1. identifies every effect lifecycle boundary between the previous update and the current time;
2. merges all effects active in each interval by state dimension;
3. applies the existing exponential transition model for that interval;
4. removes expired effects;
5. retains unrelated effects regardless of activity-channel changes.

A chat can therefore end while its fixed-time emotional effect continues during music, study, sleep, another chat, and other concurrent effects.

## Persistence and API

Aggregate JSON schema version 4 persists structured identity together with unified effects and evaluated-event markers. Schema version 3 remains readable with an explicit unspecified identity; schema versions 1 and 2 additionally migrate legacy effects into the unified model.

Person, state, and event-command responses expose:

```text
activeEffectCount
activeEffects[].effectId
activeEffects[].sourceEventId
activeEffects[].type
activeEffects[].cause
activeEffects[].startsAt
activeEffects[].endPolicy
activeEffects[].fixedEndsAt
activeEffects[].transitions
```
