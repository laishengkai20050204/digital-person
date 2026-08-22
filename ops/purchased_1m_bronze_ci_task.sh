#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: purchased_1m_bronze_ci_task.sh REMOTE_ROOT TASK_ID COMMAND..." >&2
  exit 2
fi

remote_root="$1"
task_id="$2"
shift 2

: "${SERVER_HOST:?SERVER_HOST is required}"
: "${SERVER_PORT:?SERVER_PORT is required}"
: "${SERVER_USER:?SERVER_USER is required}"
poll_seconds="${POLL_SECONDS:-30}"
[[ "$poll_seconds" =~ ^[1-9][0-9]*$ ]] || { echo "POLL_SECONDS must be positive" >&2; exit 2; }

ssh_args=(
  -i "$HOME/.ssh/id_ed25519"
  -p "$SERVER_PORT"
  -o BatchMode=yes
  -o StrictHostKeyChecking=yes
  -o ConnectTimeout=20
  -o ServerAliveInterval=10
  -o ServerAliveCountMax=3
)
remote_task="$remote_root/purchased_1m_remote_task.sh"

run_remote_task() {
  ssh "${ssh_args[@]}" "$SERVER_USER@$SERVER_HOST" \
    bash -s -- "$remote_task" "$@" <<'REMOTE'
set -euo pipefail
remote_task="$1"
shift
bash "$HOME/$remote_task" "$@"
REMOTE
}

launch="$(run_remote_task start "$remote_root" "$task_id" "$@")"
printf '%s\n' "$launch"

while true; do
  set +e
  status_output="$(run_remote_task status "$remote_root" "$task_id" 2>&1)"
  poll_rc=$?
  set -e
  state="${status_output%%$'\n'*}"

  if [ "$poll_rc" -ne 0 ]; then
    printf '%s\n' "$status_output"
    if [ "$state" = "LOST" ]; then
      echo "remote Bronze task disappeared before recording an exit code" >&2
      exit 1
    fi
    echo "Bronze task status SSH poll failed; retrying"
    sleep "$poll_seconds"
    continue
  fi

  printf '%s\n' "$status_output"
  case "$state" in
    RUNNING|STARTED\ *)
      sleep "$poll_seconds"
      ;;
    DONE\ *)
      rc="${state#DONE }"
      run_remote_task log "$remote_root" "$task_id" || true
      exit "$rc"
      ;;
    *)
      echo "unexpected remote Bronze task state: $state" >&2
      exit 1
      ;;
  esac
done
