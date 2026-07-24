#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

mkdir -p "$TEST_DIR/bin"

cat > "$TEST_DIR/bin/jq" <<'SCRIPT'
#!/usr/bin/env bash
printf 'jq' >> "$MEM0_TEST_ARGV_LOG"
printf ' <%s>' "$@" >> "$MEM0_TEST_ARGV_LOG"
printf '\n' >> "$MEM0_TEST_ARGV_LOG"
exec /usr/bin/jq "$@"
SCRIPT

cat > "$TEST_DIR/bin/curl" <<'SCRIPT'
#!/usr/bin/env bash
printf 'curl' >> "$MEM0_TEST_ARGV_LOG"
printf ' <%s>' "$@" >> "$MEM0_TEST_ARGV_LOG"
printf '\n' >> "$MEM0_TEST_ARGV_LOG"

for argument in "$@"; do
  case "$argument" in
    @*)
      referenced_file="${argument#@}"
      if [ -f "$referenced_file" ]; then
        cat "$referenced_file" >> "$MEM0_TEST_FILE_LOG"
        printf '\n' >> "$MEM0_TEST_FILE_LOG"
      fi
      ;;
  esac
done

exit 0
SCRIPT

chmod +x "$TEST_DIR/bin/jq" "$TEST_DIR/bin/curl"

cat > "$TEST_DIR/person.env" <<'ENV'
MEM0_ENABLED=true
MEM0_AUTO_INSTALL_ENABLED=true
MEM0_BASE_URL=http://127.0.0.1:8888
MEM0_LLM_API_KEY=llm-secret-DO-NOT-LEAK
MEM0_LLM_BASE_URL=https://openrouter.ai/api/v1
MEM0_LLM_MODEL=test/model
MEM0_EMBEDDER_API_KEY=embed-secret-DO-NOT-LEAK
MEM0_EMBEDDER_BASE_URL=https://embed.example/v1
MEM0_EMBEDDER_MODEL=test-embed
MEM0_API_KEY=admin-secret-DO-NOT-LEAK
ENV

: > "$TEST_DIR/argv.log"
: > "$TEST_DIR/files.log"

PATH="$TEST_DIR/bin:$PATH" \
PERSON_ENV_FILE="$TEST_DIR/person.env" \
MEM0_ENV_FILE="$TEST_DIR/missing.env" \
MEM0_TEST_ARGV_LOG="$TEST_DIR/argv.log" \
MEM0_TEST_FILE_LOG="$TEST_DIR/files.log" \
bash "$ROOT_DIR/ops/ensure-mem0.sh"

if grep -Eq 'llm-secret|embed-secret|admin-secret' "$TEST_DIR/argv.log"; then
  echo "Mem0 secret leaked through process arguments" >&2
  cat "$TEST_DIR/argv.log" >&2
  exit 1
fi

grep -q 'llm-secret-DO-NOT-LEAK' "$TEST_DIR/files.log"
grep -q 'embed-secret-DO-NOT-LEAK' "$TEST_DIR/files.log"
grep -q 'admin-secret-DO-NOT-LEAK' "$TEST_DIR/files.log"

echo "Mem0 secrets are passed through protected files, not process arguments"
