#!/usr/bin/env bash
set -Eeuo pipefail

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

# A dedicated file is preferred. The application environment file is also read
# when permissions allow it, so one set of MEM0_* variables can drive both layers.
for env_file in \
  "${PERSON_ENV_FILE:-/etc/person-ai/person-ai.env}" \
  "${MEM0_ENV_FILE:-/etc/person-ai/mem0.env}"; do
  if [ -r "$env_file" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$env_file"
    set +a
  fi
done

MEM0_AUTO_INSTALL_ENABLED="${MEM0_AUTO_INSTALL_ENABLED:-${MEM0_ENABLED:-false}}"
if ! is_true "$MEM0_AUTO_INSTALL_ENABLED"; then
  echo "Mem0 自动准备未启用，跳过"
  exit 0
fi

MEM0_VERSION="${MEM0_VERSION:-v2.0.4}"
MEM0_REPOSITORY_URL="${MEM0_REPOSITORY_URL:-https://github.com/mem0ai/mem0.git}"
MEM0_INSTALL_DIR="${MEM0_INSTALL_DIR:-/opt/person-ai/mem0}"
MEM0_SOURCE_DIR="$MEM0_INSTALL_DIR/source"
MEM0_BASE_URL="${MEM0_BASE_URL:-http://127.0.0.1:8888}"
MEM0_HEALTH_URL="${MEM0_HEALTH_URL:-${MEM0_BASE_URL%/}/auth/setup-status}"
MEM0_START_TIMEOUT_SECONDS="${MEM0_START_TIMEOUT_SECONDS:-180}"
MEM0_OPENAI_API_KEY="${MEM0_OPENAI_API_KEY:-${OPENAI_API_KEY:-}}"
MEM0_ADMIN_API_KEY="${MEM0_API_KEY:-${ADMIN_API_KEY:-}}"
MEM0_JWT_SECRET="${MEM0_JWT_SECRET:-${JWT_SECRET:-}}"

if curl -fsS --max-time 3 "$MEM0_HEALTH_URL" >/dev/null 2>&1; then
  echo "Mem0 已运行：$MEM0_BASE_URL"
  exit 0
fi

for command_name in git docker curl openssl; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Mem0 自动准备失败：缺少命令 $command_name" >&2
    exit 1
  fi
done

if ! docker compose version >/dev/null 2>&1; then
  echo "Mem0 自动准备失败：缺少 Docker Compose v2" >&2
  exit 1
fi

mkdir -p "$MEM0_INSTALL_DIR"

if [ ! -d "$MEM0_SOURCE_DIR/.git" ]; then
  rm -rf "$MEM0_SOURCE_DIR"
  echo "下载 Mem0 $MEM0_VERSION"
  git clone \
    --depth 1 \
    --branch "$MEM0_VERSION" \
    "$MEM0_REPOSITORY_URL" \
    "$MEM0_SOURCE_DIR"
else
  echo "校准 Mem0 源码版本：$MEM0_VERSION"
  git -C "$MEM0_SOURCE_DIR" fetch \
    --depth 1 \
    origin \
    "refs/tags/$MEM0_VERSION:refs/tags/$MEM0_VERSION"
  git -C "$MEM0_SOURCE_DIR" checkout --detach "refs/tags/$MEM0_VERSION"
fi

SERVER_DIR="$MEM0_SOURCE_DIR/server"
if [ ! -d "$SERVER_DIR" ]; then
  echo "Mem0 源码缺少 server 目录：$SERVER_DIR" >&2
  exit 1
fi

COMPOSE_FILE=""
for candidate in \
  "$SERVER_DIR/docker-compose.yaml" \
  "$SERVER_DIR/docker-compose.yml" \
  "$SERVER_DIR/compose.yaml" \
  "$SERVER_DIR/compose.yml"; do
  if [ -f "$candidate" ]; then
    COMPOSE_FILE="$candidate"
    break
  fi
done

if [ -z "$COMPOSE_FILE" ]; then
  echo "Mem0 server 目录中没有找到 Compose 文件" >&2
  exit 1
fi

ENV_FILE="$SERVER_DIR/.env"
umask 077

if [ -z "$MEM0_OPENAI_API_KEY" ]; then
  echo "Mem0 已下载，但缺少 MEM0_OPENAI_API_KEY/OPENAI_API_KEY，暂不启动" >&2
  echo "请在受保护的环境文件中配置供应商密钥" >&2
  exit 1
fi

if [ -z "$MEM0_ADMIN_API_KEY" ]; then
  MEM0_ADMIN_API_KEY="$(openssl rand -hex 32)"
  printf '%s\n' "$MEM0_ADMIN_API_KEY" > "$MEM0_INSTALL_DIR/generated-admin-api-key"
  chmod 600 "$MEM0_INSTALL_DIR/generated-admin-api-key"
  echo "已生成 Mem0 管理 API Key，保存在：$MEM0_INSTALL_DIR/generated-admin-api-key"
fi

if [ -z "$MEM0_JWT_SECRET" ]; then
  MEM0_JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
fi

cat > "$ENV_FILE" <<ENV
OPENAI_API_KEY=$MEM0_OPENAI_API_KEY
JWT_SECRET=$MEM0_JWT_SECRET
ADMIN_API_KEY=$MEM0_ADMIN_API_KEY
AUTH_DISABLED=false
ENV
chmod 600 "$ENV_FILE"

echo "启动 Mem0 $MEM0_VERSION"
docker compose \
  --project-directory "$SERVER_DIR" \
  -f "$COMPOSE_FILE" \
  up -d --build

attempts=$((MEM0_START_TIMEOUT_SECONDS / 3))
if [ "$attempts" -lt 1 ]; then
  attempts=1
fi

for attempt in $(seq 1 "$attempts"); do
  if curl -fsS --max-time 3 "$MEM0_HEALTH_URL" >/dev/null 2>&1; then
    echo "Mem0 启动成功：$MEM0_BASE_URL"
    exit 0
  fi
  echo "等待 Mem0 启动：$attempt/$attempts"
  sleep 3
done

echo "Mem0 启动后未通过健康检查" >&2
docker compose \
  --project-directory "$SERVER_DIR" \
  -f "$COMPOSE_FILE" \
  logs --tail=200 || true
exit 1
