#!/usr/bin/env bash
set -Eeuo pipefail

ENV_FILE="${PERSON_AI_ENV_FILE:-/etc/person-ai/person-ai.env}"
DRY_RUN=false

usage() {
  cat <<'EOF'
Usage:
  sudo bash ops/mem0-delete-memories.sh [--dry-run] <memory-id> [memory-id ...]

The script reads MEM0_BASE_URL and MEM0_API_KEY from /etc/person-ai/person-ai.env
unless PERSON_AI_ENV_FILE points to another environment file.
EOF
}

if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=true
  shift
fi

if [ "$#" -eq 0 ]; then
  usage
  exit 2
fi

if [ ! -r "$ENV_FILE" ]; then
  echo "Cannot read environment file: $ENV_FILE" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${MEM0_BASE_URL:?MEM0_BASE_URL is required in $ENV_FILE}"
: "${MEM0_API_KEY:?MEM0_API_KEY is required in $ENV_FILE}"

BASE_URL="${MEM0_BASE_URL%/}"

for memory_id in "$@"; do
  if [[ ! "$memory_id" =~ ^[A-Za-z0-9._:-]+$ ]]; then
    echo "Unsafe memory id rejected: $memory_id" >&2
    exit 2
  fi

  if [ "$DRY_RUN" = true ]; then
    echo "Would delete Mem0 memory: $memory_id"
    continue
  fi

  echo "Deleting Mem0 memory: $memory_id"
  curl --fail --silent --show-error \
    -X DELETE \
    -H "Accept: application/json" \
    -H "X-API-Key: ${MEM0_API_KEY}" \
    "${BASE_URL}/memories/${memory_id}"
  echo
 done
