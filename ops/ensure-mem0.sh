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
MEM0_LLM_API_KEY="${MEM0_LLM_API_KEY:-${MEM0_OPENAI_API_KEY:-${LLM_API_KEY:-${OPENAI_API_KEY:-}}}}"
MEM0_LLM_BASE_URL="${MEM0_LLM_BASE_URL:-${LLM_BASE_URL:-https://api.openai.com/v1}}"
MEM0_LLM_MODEL="${MEM0_LLM_MODEL:-${LLM_MODEL:-gpt-4.1-nano-2025-04-14}}"
MEM0_EMBEDDER_API_KEY="${MEM0_EMBEDDER_API_KEY:-${MEM0_OPENAI_API_KEY:-${OPENAI_API_KEY:-}}}"
MEM0_EMBEDDER_BASE_URL="${MEM0_EMBEDDER_BASE_URL:-https://api.openai.com/v1}"
MEM0_EMBEDDER_MODEL="${MEM0_EMBEDDER_MODEL:-text-embedding-3-small}"
MEM0_ADMIN_API_KEY="${MEM0_API_KEY:-${ADMIN_API_KEY:-}}"
MEM0_JWT_SECRET="${MEM0_JWT_SECRET:-${JWT_SECRET:-}}"
GENERATED_ADMIN_KEY_FILE="$MEM0_INSTALL_DIR/generated-admin-api-key"
SECRET_WORK_DIR=""

if [ -z "$MEM0_EMBEDDER_API_KEY" ] && [ "$MEM0_LLM_BASE_URL" = "https://api.openai.com/v1" ]; then
  MEM0_EMBEDDER_API_KEY="$MEM0_LLM_API_KEY"
fi

if [ -z "$MEM0_ADMIN_API_KEY" ] && [ -r "$GENERATED_ADMIN_KEY_FILE" ]; then
  MEM0_ADMIN_API_KEY="$(tr -d '\r\n' < "$GENERATED_ADMIN_KEY_FILE")"
fi

# Keep provider and admin secrets out of child-process environments. Values remain
# shell-local and are passed through protected files or stdin only.
for secret_name in \
  MEM0_LLM_API_KEY MEM0_OPENAI_API_KEY LLM_API_KEY OPENAI_API_KEY \
  MEM0_EMBEDDER_API_KEY MEM0_API_KEY ADMIN_API_KEY \
  MEM0_JWT_SECRET JWT_SECRET; do
  export -n "$secret_name" 2>/dev/null || true
done

for command_name in curl jq mktemp; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Mem0 自动准备失败：缺少命令 $command_name" >&2
    exit 1
  fi
done

if [ -z "$MEM0_LLM_API_KEY" ]; then
  echo "Mem0 缺少记忆提取模型密钥：请配置 MEM0_LLM_API_KEY 或 LLM_API_KEY" >&2
  exit 1
fi

if [ -z "$MEM0_EMBEDDER_API_KEY" ]; then
  echo "Mem0 还需要独立的 embedding API Key：请配置 MEM0_EMBEDDER_API_KEY" >&2
  echo "OpenRouter 不能作为 embedding 服务；可使用 OpenAI 或其他 OpenAI 兼容 embedding 接口" >&2
  exit 1
fi

cleanup_secret_files() {
  if [ -n "$SECRET_WORK_DIR" ] && [ -d "$SECRET_WORK_DIR" ]; then
    rm -rf "$SECRET_WORK_DIR"
  fi
}
trap cleanup_secret_files EXIT

ensure_secret_work_dir() {
  if [ -z "$SECRET_WORK_DIR" ]; then
    SECRET_WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/person-ai-mem0.XXXXXX")"
    chmod 700 "$SECRET_WORK_DIR"
  fi
}

write_secret_file() {
  local target="$1"
  local value="$2"

  ensure_secret_work_dir
  umask 077
  printf '%s' "$value" > "$target"
  chmod 600 "$target"
}

build_config_payload() {
  ensure_secret_work_dir

  local llm_key_file="$SECRET_WORK_DIR/llm-api-key"
  local embedder_key_file="$SECRET_WORK_DIR/embedder-api-key"

  write_secret_file "$llm_key_file" "$MEM0_LLM_API_KEY"
  write_secret_file "$embedder_key_file" "$MEM0_EMBEDDER_API_KEY"

  jq -n \
    --rawfile llm_key "$llm_key_file" \
    --arg llm_base "$MEM0_LLM_BASE_URL" \
    --arg llm_model "$MEM0_LLM_MODEL" \
    --rawfile embedder_key "$embedder_key_file" \
    --arg embedder_base "$MEM0_EMBEDDER_BASE_URL" \
    --arg embedder_model "$MEM0_EMBEDDER_MODEL" \
    '{
      llm: {
        provider: "openai",
        config: {
          api_key: $llm_key,
          openai_base_url: $llm_base,
          model: $llm_model,
          temperature: 0.2
        }
      },
      embedder: {
        provider: "openai",
        config: {
          api_key: $embedder_key,
          openai_base_url: $embedder_base,
          model: $embedder_model
        }
      }
    }'
}

apply_mem0_configuration() {
  if [ -z "$MEM0_ADMIN_API_KEY" ]; then
    echo "Mem0 已运行，但缺少 MEM0_API_KEY/ADMIN_API_KEY，无法写入受保护的 /configure" >&2
    return 1
  fi

  ensure_secret_work_dir

  local admin_header_file="$SECRET_WORK_DIR/admin-header"
  local config_payload_file="$SECRET_WORK_DIR/config-payload.json"

  write_secret_file "$admin_header_file" "X-API-Key: $MEM0_ADMIN_API_KEY"
  build_config_payload > "$config_payload_file"
  chmod 600 "$config_payload_file"

  curl -fsS \
    --max-time 30 \
    -X POST \
    -H @"$admin_header_file" \
    -H "Content-Type: application/json" \
    --data-binary @"$config_payload_file" \
    "${MEM0_BASE_URL%/}/configure" >/dev/null
}

if curl -fsS --max-time 3 "$MEM0_HEALTH_URL" >/dev/null 2>&1; then
  echo "Mem0 已运行，重新应用 LLM/embedding 配置：$MEM0_BASE_URL"
  if ! apply_mem0_configuration; then
    echo "Mem0 服务可访问，但配置刷新失败" >&2
    exit 1
  fi
  echo "Mem0 配置已更新"
  exit 0
fi

for command_name in git docker openssl; do
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
# Docker Compose may inspect the inherited current directory even when all project
# paths are absolute. Use a deploy-owned directory instead of /home/ubuntu.
cd "$MEM0_INSTALL_DIR"

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

if [ -z "$MEM0_ADMIN_API_KEY" ]; then
  MEM0_ADMIN_API_KEY="$(openssl rand -hex 32)"
  printf '%s\n' "$MEM0_ADMIN_API_KEY" > "$GENERATED_ADMIN_KEY_FILE"
  chmod 600 "$GENERATED_ADMIN_KEY_FILE"
  echo "已生成 Mem0 管理 API Key，保存在：$GENERATED_ADMIN_KEY_FILE"
fi

if [ -z "$MEM0_JWT_SECRET" ]; then
  MEM0_JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
fi

cat > "$ENV_FILE" <<ENV
OPENAI_API_KEY=$MEM0_LLM_API_KEY
MEM0_DEFAULT_LLM_MODEL=$MEM0_LLM_MODEL
MEM0_DEFAULT_EMBEDDER_MODEL=$MEM0_EMBEDDER_MODEL
JWT_SECRET=$MEM0_JWT_SECRET
ADMIN_API_KEY=$MEM0_ADMIN_API_KEY
AUTH_DISABLED=false
ENV
chmod 600 "$ENV_FILE"

echo "启动 Mem0 $MEM0_VERSION"
# Digital Person only consumes the Mem0 HTTP API. Targeting the mem0 service also
# starts PostgreSQL through depends_on, while avoiding the optional dashboard build.
docker compose \
  --project-directory "$SERVER_DIR" \
  -f "$COMPOSE_FILE" \
  up -d --build mem0

attempts=$((MEM0_START_TIMEOUT_SECONDS / 3))
if [ "$attempts" -lt 1 ]; then
  attempts=1
fi

for attempt in $(seq 1 "$attempts"); do
  if curl -fsS --max-time 3 "$MEM0_HEALTH_URL" >/dev/null 2>&1; then
    if ! apply_mem0_configuration; then
      echo "Mem0 已启动，但模型/向量配置写入失败" >&2
      exit 1
    fi

    echo "Mem0 启动成功并已应用 LLM/embedding 配置：$MEM0_BASE_URL"
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
