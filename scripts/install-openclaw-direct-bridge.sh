#!/usr/bin/env bash
set -euo pipefail

export PATH="$HOME/.npm-global/bin:$PATH"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_DIR="$ROOT_DIR/openclaw-plugins/digital-person-direct-bridge"
CONFIG_PATH="${OPENCLAW_CONFIG_PATH:-$HOME/.openclaw/openclaw.json}"
PLUGIN_ID="digital-person-direct-bridge"

if ! command -v openclaw >/dev/null 2>&1; then
  echo "openclaw command was not found" >&2
  exit 1
fi
if [[ ! -f "$PLUGIN_DIR/openclaw.plugin.json" ]]; then
  echo "plugin directory is incomplete: $PLUGIN_DIR" >&2
  exit 1
fi
if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "OpenClaw config was not found: $CONFIG_PATH" >&2
  exit 1
fi

mkdir -p "$(dirname "$CONFIG_PATH")"
BACKUP_PATH="$CONFIG_PATH.bak.$(date +%Y%m%d-%H%M%S)"
cp "$CONFIG_PATH" "$BACKUP_PATH"
echo "Config backup: $BACKUP_PATH"

if openclaw plugins inspect "$PLUGIN_ID" >/dev/null 2>&1; then
  echo "Plugin is already registered; keeping the existing linked install."
else
  openclaw plugins install --link "$PLUGIN_DIR"
fi

python3 - "$CONFIG_PATH" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
config = json.loads(path.read_text(encoding="utf-8"))
plugins = config.setdefault("plugins", {})
entries = plugins.setdefault("entries", {})
entry = entries.setdefault("digital-person-direct-bridge", {})
entry["enabled"] = True
entry["config"] = {
    "channelId": "openclaw-weixin",
    "endpoint": "http://127.0.0.1:8080/v1/chat/completions",
    "providerId": "digitalperson",
    "model": "shen-zhixia",
    "apiKeyEnv": "PERSON_API_TOKEN",
    "timeoutMs": 220000,
    "directMessagesOnly": True,
    "dedupeTtlMs": 600000,
    "backendErrorMessage": "后端暂时不可用，请稍后再试。",
    "unsupportedMessage": "暂时只能处理文字消息。",
}
entry["hooks"] = {
    "timeouts": {
        "inbound_claim": 240000,
    }
}

# Preserve an absent allowlist. If the operator already uses an explicit
# allowlist, add this trusted local plugin without removing any existing ids.
allow = plugins.get("allow")
if isinstance(allow, list) and "digital-person-direct-bridge" not in allow:
    allow.append("digital-person-direct-bridge")

path.write_text(
    json.dumps(config, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY

openclaw config validate
openclaw gateway restart
sleep 6

openclaw gateway status --deep
openclaw plugins inspect "$PLUGIN_ID" --runtime --json

cat <<'EOF'

Installation completed.

Verify in WeChat:
  1. Send a normal message, for example: 你在干嘛
  2. Send: #dp activity
  3. OpenClaw commands such as /status remain handled by OpenClaw.

Verify that normal messages do not start a model call:
  journalctl --user -u openclaw-gateway.service --since "5 minutes ago" -o cat \
    | grep -E 'digital-person-direct-bridge|model-fetch|Auto-compaction'

A successful direct turn should show digital-person-direct-bridge and no
model-fetch/Auto-compaction line for that message.
EOF
