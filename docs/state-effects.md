# Unified State Effects

The state system separates facts from their consequences:

- an `Event` records what happened and participates in activity-channel concurrency;
- a model-produced `StateEffect` records a short-term consequence of one event;
- Java-owned natural evolution continuously models physiology and baseline recovery;
- `PersonState` is settled to each command boundary from both layers.

Activity channels never own or overwrite state effects.

## Persisted effect shape

Each persisted event effect contains:

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

The persisted transition still contains a resolved numeric `shape`. A positive value moves toward the dimension maximum, a negative value moves toward the minimum, and the absolute value is an hourly exponential rate rather than a direct delta.

The hard domain bound is `36.0` so the explicit `INSTANT` tier can approach a bound within several minutes. Ordinary model effects are constrained by semantic tiers and never receive an unrestricted numeric input.

## Model-facing intensity protocol

The model submits direction and intensity instead of raw shape:

```json
{
  "dimension": "TENSION",
  "direction": "INCREASE",
  "intensity": "HIGH"
}
```

Java resolves these deterministic ranges:

```text
LOW      0.08-0.12 / hour
MEDIUM   0.20-0.30 / hour
HIGH     0.40-0.60 / hour
EXTREME  0.80-1.20 / hour
INSTANT  24.0-36.0 / hour
```

The seed includes the person, source event, transition position, dimension, direction and tier. Retries and restarts therefore reproduce the same resolved rate while separate events retain small variation.

Lifecycle validation is strict:

- `EVENT_END` permits only `LOW` or `MEDIUM` because its duration is unknown;
- fixed `HIGH` effects may last at most 180 minutes;
- `EXTREME` may last at most 60 minutes;
- `INSTANT` requires `FIXED_TIME` and may last at most 10 minutes.

## Effect types

```text
EMOTIONAL  -> VALENCE, ENERGY, TENSION
COGNITIVE  -> FOCUS, MENTAL_LOAD, MOTIVATION
PHYSICAL   -> FATIGUE, SLEEPINESS, HUNGER
SOCIAL     -> LONELINESS, SOCIAL_NEED
```

One event may register several effects with independent causes and lifecycles.

## End policies

### `EVENT_END`

Stops exactly when the bound source event ends or is replaced. Because the duration is unknown, only low or medium model intensity is accepted.

### `FIXED_TIME`

Stops at `fixedEndsAt` and does not end merely because its source event finished.

### `EVENT_END_OR_FIXED_TIME`

Stops at the earlier of source-event completion and the fixed deadline.

## Event evaluation protocol

Production evaluation must call `submit_state_effects` exactly once:

```json
{
  "effects": [
    {
      "type": "COGNITIVE",
      "cause": "激烈沟通持续占用注意力",
      "transitions": [
        {
          "dimension": "MENTAL_LOAD",
          "direction": "INCREASE",
          "intensity": "MEDIUM"
        }
      ],
      "endPolicy": "EVENT_END",
      "durationMinutes": 0
    },
    {
      "type": "EMOTIONAL",
      "cause": "关系破裂消息引发持续低落",
      "transitions": [
        {
          "dimension": "VALENCE",
          "direction": "DECREASE",
          "intensity": "EXTREME"
        }
      ],
      "endPolicy": "FIXED_TIME",
      "durationMinutes": 60
    }
  ]
}
```

A valid no-effect evaluation returns:

```json
{"effects": []}
```

An evaluated-event marker is stored separately, so an empty result is not mistaken for an event that was never evaluated.

## Java-owned natural evolution

Natural evolution is logically permanent but is not stored as an ordinary `RegisteredStateEffect`. Its targets depend on time and context:

- eating moves hunger toward a low target;
- time since the last meal moves hunger toward a progressively higher target;
- local circadian time, awake duration and recent sleep debt determine sleepiness;
- sleep, exercise, study, work and rest use different energy and fatigue targets;
- sleep and non-work periods return cognitive dimensions toward baselines.

Natural transitions use the same exponential model with an arbitrary target:

```text
next = target + (current - target) * exp(-hourlyRate * elapsedHours)
```

Targets and rates receive small deterministic daily variation per person and dimension. Settlement is split into 15-minute intervals so event and circadian boundaries are respected without frequent database writes.

Ordinary sleep recovery, hunger accumulation, eating-related hunger reduction and cognitive baseline regression must not be duplicated by the LLM. The model may still add short-term effects when a specific abnormal or contextual cause exists.

## Settlement order

At each command boundary, the updater:

1. settles Java-owned natural evolution from the previous update time;
2. identifies every registered effect lifecycle boundary in the same interval;
3. merges active registered effects by state dimension;
4. applies event effects;
5. removes expired effects and retains unrelated effects.

## Persistence and API

No persistence schema migration is required. Aggregate JSON continues to store the resolved numeric transitions and evaluated-event markers. APIs continue to expose numeric `activeEffects[].transitions`, which represent the Java-resolved rates actually being applied.
