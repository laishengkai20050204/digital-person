# Conversation Episode Memory

## Purpose

Conversation episode memory preserves complete historical events that would lose their causal structure if they were reduced to independent Mem0 facts.

The dialogue context now has four complementary layers:

```text
recent raw turns
+ rolling conversation summary
+ relevant conversation episodes
+ atomic long-term memories from Mem0
```

An episode is appropriate for a completed conflict, decision, plan, achievement, important experience, relationship change, or other event with enough context to understand what happened and what resulted.

It is not appropriate for greetings, transient confirmations, unfinished topics, synthetic tests, test codes, credentials, or isolated stable facts.

## Extraction lifecycle

Episodes reuse the stable batch boundary already established by rolling summaries:

```text
unsummarized turns reach recent-window + batch threshold
                    ↓
prepare one optimistic summary work item
                    ↓
summary generation and episode extraction run in parallel
                    ↓
commit rolling summary with version / coverage CAS
                    ↓
only the winner persists extracted episodes
```

This ordering provides three guarantees:

1. Episodes are extracted only from turns old enough to leave the recent raw window.
2. A request that loses the summary optimistic race cannot persist duplicate or stale episodes.
3. Episode extraction and persistence failures do not block normal dialogue or rolling summaries.

## Persistence

Flyway migration `V5__create_person_conversation_episode.sql` creates `person_conversation_episode`.

Each row stores:

- source start and end turn IDs;
- title and event type;
- event summary;
- participants and emotions;
- outcome;
- importance from `0.0` to `1.0`;
- event time range and creation time.

The unique key on person, source range, and title makes retries idempotent. Rows are deleted automatically when the owning digital person is deleted.

## Retrieval

The MySQL adapter first loads a bounded set of recent candidates, then ranks them using:

```text
query lexical overlap
+ episode importance
+ recency
```

Chinese text is compared using Unicode-normalized two-character terms. Non-Chinese terms use normalized word tokens. By default, at most three episodes are injected for one dialogue request.

Retrieved episodes are converted to synthetic `EPISODE` conversation items. The dialogue model receives context in this order:

```text
system instructions and structured person context
rolling summary
relevant historical episodes
recent raw user/person turns
current user message
```

Episodes are supplied as ordinary user-role background data, not system messages. Current user input and later raw turns override conflicting episode content.

## Configuration

```bash
CONVERSATION_EPISODE_ENABLED=true
CONVERSATION_EPISODE_MAX_ITEMS=3
CONVERSATION_EPISODE_MAX_OUTPUT_TOKENS=700
CONVERSATION_EPISODE_TEMPERATURE=0.1
```

`CONVERSATION_EPISODE_ENABLED=false` disables extraction and retrieval while leaving raw-turn persistence, rolling summaries, and Mem0 unchanged.

## Production verification

After deployment, confirm Flyway V5 and the new table:

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;

SHOW CREATE TABLE person_conversation_episode;
```

Inspect recent episode rows without exposing full private summaries:

```sql
SELECT
  episode_id,
  person_id,
  source_start_turn_id,
  source_end_turn_id,
  title,
  event_type,
  importance,
  CHAR_LENGTH(summary_text) AS summary_length,
  started_at,
  ended_at,
  created_at
FROM person_conversation_episode
WHERE person_id = '<person-id>'
ORDER BY episode_id DESC
LIMIT 20;
```

Relevant service logs:

```text
Conversation episodes persisted
Conversation episode extraction failed
Conversation episode persistence failed
```

The failure logs contain only person IDs, stages, counts, source turn IDs, and exception types; episode text is not logged.
