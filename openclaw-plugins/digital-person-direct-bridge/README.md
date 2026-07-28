# Digital Person Direct WeChat Bridge

This OpenClaw plugin keeps WeChat transport while bypassing the OpenClaw agent/session loop for ordinary Digital Person messages.

## Routing

```text
WeChat ordinary text or #dp command
  -> OpenClaw inbound_claim hook
  -> POST one current user message to /v1/chat/completions
  -> return the Java reply directly to WeChat

WeChat /new, /status, /compact, ...
  -> OpenClaw command handling
```

Because ordinary messages never start an OpenClaw agent turn, they do not load OpenClaw history, tools, system prompts, or compaction. Java remains the single owner of dialogue history, rolling summaries, Mem0, structured memory, state, and activities.

## Defaults

- Channel: `openclaw-weixin`
- Endpoint: `http://127.0.0.1:8080/v1/chat/completions`
- Provider config fallback: `models.providers.digitalperson.apiKey`
- Environment fallback: `PERSON_API_TOKEN`
- Model: `shen-zhixia`
- Backend timeout: 220 seconds
- Direct messages only
- Successful message-id deduplication: 10 minutes

The bridge fails closed. If Java is unavailable, it returns a short fixed error instead of falling through to the OpenClaw agent path. This prevents unexpected compaction and duplicate backend calls.

## Install

Run from the repository root on the server:

```bash
bash scripts/install-openclaw-direct-bridge.sh
```

Then verify:

```bash
openclaw plugins inspect digital-person-direct-bridge --runtime --json
journalctl --user -u openclaw-gateway.service --since "5 minutes ago" -o cat \
  | grep digital-person-direct-bridge
```

Send an ordinary WeChat message and a command:

```text
你在干嘛
#dp activity
```

Both should be handled without a provider/model call in the OpenClaw gateway log.

## Rollback

```bash
openclaw plugins disable digital-person-direct-bridge
openclaw gateway restart
```

After rollback, ordinary WeChat messages return to the original OpenClaw agent/session path.

## Configuration

Plugin config lives under:

```json
{
  "plugins": {
    "entries": {
      "digital-person-direct-bridge": {
        "enabled": true,
        "config": {
          "channelId": "openclaw-weixin",
          "endpoint": "http://127.0.0.1:8080/v1/chat/completions",
          "providerId": "digitalperson",
          "model": "shen-zhixia",
          "apiKeyEnv": "PERSON_API_TOKEN",
          "timeoutMs": 220000,
          "directMessagesOnly": true,
          "dedupeTtlMs": 600000
        },
        "hooks": {
          "timeouts": {
            "inbound_claim": 240000
          }
        }
      }
    }
  }
}
```

An explicit `apiKey` can be supplied, but reusing the configured provider key or an environment variable avoids duplicating secrets.
