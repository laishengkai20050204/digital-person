# Person Event API

The person event API is available only when both the protected person API and `PersonEventCommandService` are active.

```bash
export PERSON_API_ENABLED=true
export PERSON_API_TOKEN='use-a-long-random-token'
```

Every request requires:

```text
X-Internal-Token: use-a-long-random-token
```

The examples below assume:

```bash
PERSON_ID='4bfea51f-8c59-47da-846b-7c84bec71ff7'
```

## Start a realtime event

The server supplies the authoritative UTC start time. Clients must not provide a start timestamp.

```bash
curl -sS -X POST \
  -H 'Content-Type: application/json' \
  -H "X-Internal-Token: $PERSON_API_TOKEN" \
  -d '{
    "activityType": "LISTEN_MUSIC",
    "title": "听音乐",
    "location": "宿舍",
    "participants": [],
    "notes": ""
  }' \
  "http://127.0.0.1:8080/api/persons/$PERSON_ID/events/realtime"
```

Starting a new event in the same activity channel replaces the previous open event at the command boundary. Existing state effects are settled first, and the new event is evaluated before one optimistic-lock commit.

Supported activity types:

```text
STUDY WORK EAT SLEEP REST TRAVEL EXERCISE SOCIAL ENTERTAINMENT SHOPPING
CHAT LISTEN_MUSIC OTHER
```

## Finish a realtime event

Use the `eventId` returned by the start response.

```bash
EVENT_ID='replace-with-event-id'

curl -sS -X POST \
  -H 'Content-Type: application/json' \
  -H "X-Internal-Token: $PERSON_API_TOKEN" \
  -d '{"reason":"COMPLETED"}' \
  "http://127.0.0.1:8080/api/persons/$PERSON_ID/events/$EVENT_ID/finish"
```

Clients may use `COMPLETED` or `INTERRUPTED`. `REPLACED` is reserved for automatic same-channel replacement.

## Record a historical event

Historical recording writes the event timeline only. It does not replay the event or retroactively change current short-term state.

```bash
curl -sS -X POST \
  -H 'Content-Type: application/json' \
  -H "X-Internal-Token: $PERSON_API_TOKEN" \
  -d '{
    "activityType": "STUDY",
    "title": "复习数据结构",
    "location": "图书馆",
    "startTime": "2026-07-22T10:00:00Z",
    "endTime": "2026-07-22T11:30:00Z",
    "participants": [],
    "notes": ""
  }' \
  "http://127.0.0.1:8080/api/persons/$PERSON_ID/events/history"
```

The historical `endTime` must not be after the server registration time, and it must be strictly after `startTime`.

## Response

Successful commands return the committed event together with the current state and active state-effect channels:

```json
{
  "personId": "...",
  "eventId": "...",
  "activityType": "LISTEN_MUSIC",
  "channel": "AUDIO",
  "title": "听音乐",
  "location": "宿舍",
  "startTime": "2026-07-22T13:00:00Z",
  "endTime": null,
  "endReason": null,
  "participants": [],
  "notes": "",
  "state": {},
  "stateLastUpdatedAt": "2026-07-22T13:00:00Z",
  "activeEffectChannels": ["AUDIO"]
}
```

## Stable errors

```text
400 INVALID_REQUEST
401 UNAUTHORIZED
404 PERSON_NOT_FOUND
409 PERSON_VERSION_CONFLICT
409 PERSON_EVENT_STATE_UNSETTLED
409 EVENT_STATE_CONFLICT
```
