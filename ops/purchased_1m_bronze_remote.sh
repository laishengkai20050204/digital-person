#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: purchased_1m_bronze_remote.sh SCRIPT python-args..." >&2
  exit 2
fi

script="$1"
shift
python="${ALPHAVECTOR_RUNTIME_PYTHON:-$HOME/.cache/alphavector-theme-resolver/venv/bin/python}"
runtime_env="$HOME/.config/alphavector/runtime.env"
private_repo="$HOME/.cache/alphavector-theme-resolver/repo"

[ -x "$python" ] || {
  echo "AlphaVector runtime Python is missing: $python" >&2
  exit 2
}
[ -r "$script" ] || {
  echo "Bronze builder script is missing: $script" >&2
  exit 2
}

if [ -f "$runtime_env" ]; then
  set +u
  set -a
  # shellcheck disable=SC1090
  source "$runtime_env"
  set +a
  set -u
fi

if [ -z "${TENCENT_SECRET_ID:-}" ] || [ -z "${TENCENT_SECRET_KEY:-}" ]; then
  set +u
  for startup in "$HOME/.profile" "$HOME/.bash_profile" "$HOME/.bashrc"; do
    if [ -f "$startup" ]; then
      # shellcheck disable=SC1090
      source "$startup" >/dev/null 2>&1 || true
    fi
  done
  set -u
fi

if [ -z "${TENCENT_SECRET_ID:-}" ] && [ -n "${TENCENTCLOUD_SECRET_ID:-}" ]; then
  export TENCENT_SECRET_ID="$TENCENTCLOUD_SECRET_ID"
fi
if [ -z "${TENCENT_SECRET_KEY:-}" ] && [ -n "${TENCENTCLOUD_SECRET_KEY:-}" ]; then
  export TENCENT_SECRET_KEY="$TENCENTCLOUD_SECRET_KEY"
fi

if [ -d "$private_repo/src" ]; then
  export PYTHONPATH="$private_repo/src${PYTHONPATH:+:$PYTHONPATH}"
fi

exec "$python" "$script" "$@"
