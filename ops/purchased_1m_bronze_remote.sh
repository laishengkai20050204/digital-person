#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: purchased_1m_bronze_remote.sh SCRIPT python-args..." >&2
  exit 2
fi

script="$1"
shift
runtime_env="$HOME/.config/alphavector/runtime.env"
runtime_root="${PURCHASED_1M_BRONZE_RUNTIME_ROOT:-$HOME/.cache/digital-person-purchased-1m-bronze/runtime}"
python="${ALPHAVECTOR_RUNTIME_PYTHON:-$runtime_root/venv/bin/python}"
shim_root="$runtime_root/shim"
controller_root="$(cd "$(dirname "$script")" && pwd)"
wheelhouse="$controller_root/wheelhouse"

[ -r "$script" ] || {
  echo "Bronze builder script is missing: $script" >&2
  exit 2
}

runtime_packages_ready() {
  [ -x "$python" ] || return 1
  "$python" - <<'PY' >/dev/null 2>&1
import pandas
import pyarrow
import qcloud_cos
PY
}

if ! runtime_packages_ready; then
  if [ -n "${ALPHAVECTOR_RUNTIME_PYTHON:-}" ]; then
    echo "Configured AlphaVector runtime Python is missing required Bronze packages: $python" >&2
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
  compgen -G "$wheelhouse/*.whl" >/dev/null || {
    echo "Bronze offline wheelhouse is missing or empty: $wheelhouse" >&2
    exit 2
  }

  mkdir -p "$runtime_root"
  exec 9>"$runtime_root/bootstrap.lock"
  flock 9

  if ! runtime_packages_ready; then
    echo "Bootstrapping dedicated Purchased 1m Bronze runtime from offline wheelhouse: $runtime_root/venv" >&2
    rm -rf "$runtime_root/venv" "$runtime_root/venv.tmp"
    python3 -m venv "$runtime_root/venv.tmp"
    "$runtime_root/venv.tmp/bin/python" -m pip install \
      --disable-pip-version-check \
      --no-input \
      --no-index \
      --find-links "$wheelhouse" \
      "pandas>=2.2,<2.4" \
      "pyarrow>=17,<22" \
      "cos-python-sdk-v5>=1.9,<2"
    rm -rf "$runtime_root/venv"
    mv "$runtime_root/venv.tmp" "$runtime_root/venv"
  fi
fi

# Bronze only needs AlphaVector's stable COS primitive. Vendor that tiny interface locally so
# pruning AlphaVector's checkout/cache cannot break source discovery or later Bronze workers.
install -d -m 700 "$shim_root/alphavector/storage"
printf '%s\n' '"""Bronze runtime compatibility package."""' > "$shim_root/alphavector/__init__.py"
printf '%s\n' 'from . import cos_client' '__all__ = ["cos_client"]' > "$shim_root/alphavector/storage/__init__.py"
cat > "$shim_root/alphavector/storage/cos_client.py" <<'PY'
"""Minimal COS client surface used by the Purchased 1m Bronze mirror."""
from __future__ import annotations

import os

DEFAULT_REGION = "ap-shanghai"
DEFAULT_BUCKET = "alphavector-training-1375268513"


def tencent_credentials() -> tuple[str, str, str | None]:
    secret_id = os.getenv("TENCENT_SECRET_ID", "").strip()
    secret_key = os.getenv("TENCENT_SECRET_KEY", "").strip()
    token = os.getenv("TENCENT_SESSION_TOKEN", "").strip() or None
    if not secret_id or not secret_key:
        raise RuntimeError(
            "TENCENT_SECRET_ID and TENCENT_SECRET_KEY must be set in the server environment"
        )
    return secret_id, secret_key, token


def create_cos_client(region: str):
    from qcloud_cos import CosConfig, CosS3Client

    secret_id, secret_key, token = tencent_credentials()
    config = CosConfig(
        Region=region,
        SecretId=secret_id,
        SecretKey=secret_key,
        Token=token,
        Scheme="https",
    )
    return CosS3Client(config)
PY

# AlphaVector currently targets Python >=3.11 and imports datetime.UTC. Ubuntu 22.04 may expose
# Python 3.10 as python3, so provide the equivalent name before the Bronze script is imported.
cat > "$shim_root/sitecustomize.py" <<'PY'
import datetime

if not hasattr(datetime, "UTC"):
    datetime.UTC = datetime.timezone.utc
PY

export PYTHONPATH="$shim_root${PYTHONPATH:+:$PYTHONPATH}"

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

"$python" - <<'PY'
import datetime
import pandas
import pyarrow
import qcloud_cos
from alphavector.storage import cos_client

assert hasattr(datetime, "UTC")
assert cos_client.DEFAULT_BUCKET == "alphavector-training-1375268513"
assert cos_client.DEFAULT_REGION == "ap-shanghai"
PY

exec "$python" "$script" "$@"
