#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: purchased_1m_bronze_remote.sh SCRIPT python-args..." >&2
  exit 2
fi

script="$1"
shift
runtime_env="$HOME/.config/alphavector/runtime.env"
private_repo="$HOME/.cache/alphavector-theme-resolver/repo"
runtime_root="${PURCHASED_1M_BRONZE_RUNTIME_ROOT:-$HOME/.cache/digital-person-purchased-1m-bronze/runtime}"
python="${ALPHAVECTOR_RUNTIME_PYTHON:-$runtime_root/venv/bin/python}"

[ -r "$script" ] || {
  echo "Bronze builder script is missing: $script" >&2
  exit 2
}

[ -d "$private_repo/src/alphavector" ] || {
  echo "AlphaVector source tree is missing: $private_repo/src/alphavector" >&2
  exit 2
}

if [ ! -x "$python" ]; then
  if [ -n "${ALPHAVECTOR_RUNTIME_PYTHON:-}" ]; then
    echo "Configured AlphaVector runtime Python is missing: $python" >&2
    exit 2
  fi
  command -v python3 >/dev/null 2>&1 || {
    echo "python3 is required to bootstrap the Bronze runtime" >&2
    exit 2
  }
  command -v flock >/dev/null 2>&1 || {
    echo "flock is required to bootstrap the Bronze runtime" >&2
    exit 2
  }

  mkdir -p "$runtime_root"
  exec 9>"$runtime_root/bootstrap.lock"
  flock 9

  if [ ! -x "$python" ]; then
    echo "Bootstrapping dedicated Purchased 1m Bronze runtime: $runtime_root/venv" >&2
    rm -rf "$runtime_root/venv.tmp"
    python3 -m venv "$runtime_root/venv.tmp"
    "$runtime_root/venv.tmp/bin/python" -m pip install \
      --disable-pip-version-check \
      --no-input \
      "pandas>=2.2" \
      "pyarrow>=17.0" \
      "cos-python-sdk-v5>=1.9"
    rm -rf "$runtime_root/venv"
    mv "$runtime_root/venv.tmp" "$runtime_root/venv"
  fi
fi

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

export PYTHONPATH="$private_repo/src${PYTHONPATH:+:$PYTHONPATH}"

"$python" - <<'PY'
import pandas
import pyarrow
import qcloud_cos
from alphavector.storage import cos_client

assert cos_client.DEFAULT_BUCKET
assert cos_client.DEFAULT_REGION
PY

exec "$python" "$script" "$@"
