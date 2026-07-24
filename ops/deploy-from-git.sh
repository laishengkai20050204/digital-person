#!/usr/bin/env bash
set -Eeuo pipefail

APP_SHA="${APP_SHA:?APP_SHA is required}"
APP_DIR="${APP_DIR:-/opt/person-ai}"
DEPLOY_BRANCH="${DEPLOY_BRANCH:-main}"
REPOSITORY_URL="${REPOSITORY_URL:-git@github.com:laishengkai20050204/digital-person.git}"
SERVICE_NAME="${SERVICE_NAME:-person-ai}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
DEPLOY_KEY="${DEPLOY_KEY:-$HOME/.ssh/github-readonly}"
REQUIRED_JAVA_MAJOR=21

SOURCE_REPO="$APP_DIR/source.git"
BUILD_ROOT="$APP_DIR/builds"
BUILD_DIR="$BUILD_ROOT/$APP_SHA"
RELEASE_DIR="$APP_DIR/releases"
CURRENT_LINK="$APP_DIR/current.jar"
MAVEN_CACHE="$APP_DIR/maven-repository"
LOCK_FILE="$APP_DIR/deploy.lock"
NEW_JAR="$RELEASE_DIR/person-ai-$APP_SHA.jar"

if [[ ! "$APP_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "APP_SHA 必须是完整的 40 位小写 Git commit SHA"
  exit 1
fi

is_required_jdk() {
  local candidate="$1"
  local javac_version
  local javac_major

  [ -x "$candidate/bin/java" ] && [ -x "$candidate/bin/javac" ] || return 1

  javac_version="$("$candidate/bin/javac" -version 2>&1 | awk '{print $2}')"
  javac_major="${javac_version%%.*}"
  [ "$javac_major" = "$REQUIRED_JAVA_MAJOR" ]
}

SELECTED_JAVA_HOME=""
JAVA_HOME_CANDIDATES=()

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_HOME_CANDIDATES+=("$JAVA_HOME")
fi

shopt -s nullglob
JAVA_HOME_CANDIDATES+=(
  /usr/lib/jvm/java-21-openjdk-*
  /usr/lib/jvm/temurin-21-*
  /usr/lib/jvm/jdk-21-*
)
shopt -u nullglob

for candidate in "${JAVA_HOME_CANDIDATES[@]}"; do
  if is_required_jdk "$candidate"; then
    SELECTED_JAVA_HOME="$candidate"
    break
  fi
done

if [ -z "$SELECTED_JAVA_HOME" ]; then
  echo "服务器没有可用的完整 JDK 21（必须同时包含 java 和 javac）"
  echo "Ubuntu 可安装：sudo apt-get install -y openjdk-21-jdk"
  exit 1
fi

export JAVA_HOME="$SELECTED_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

echo "部署构建使用 JAVA_HOME：$JAVA_HOME"
java -version
javac -version

for command_name in git mvn java javac curl flock; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "服务器缺少命令：$command_name"
    exit 1
  fi
done

if ! mvn -version | grep -q "Java version: 21"; then
  echo "Maven 未使用 Java 21，拒绝继续部署"
  mvn -version || true
  exit 1
fi

if [ ! -r "$DEPLOY_KEY" ]; then
  echo "缺少只读 GitHub Deploy Key：$DEPLOY_KEY"
  exit 1
fi

mkdir -p "$APP_DIR" "$BUILD_ROOT" "$RELEASE_DIR" "$MAVEN_CACHE"

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "另一个部署正在执行，拒绝并发部署"
  exit 1
fi

if [ -z "${GIT_SSH_COMMAND:-}" ]; then
  export GIT_SSH_COMMAND="ssh -i $DEPLOY_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes"
fi

if [ ! -d "$SOURCE_REPO" ]; then
  echo "首次部署：克隆只读源仓库"
  git clone --bare "$REPOSITORY_URL" "$SOURCE_REPO"
else
  if [ ! -f "$SOURCE_REPO/HEAD" ]; then
    echo "源码目录存在但不是有效的 bare Git 仓库：$SOURCE_REPO"
    exit 1
  fi
  git --git-dir="$SOURCE_REPO" remote set-url origin "$REPOSITORY_URL"
fi

echo "拉取 origin/$DEPLOY_BRANCH"
git --git-dir="$SOURCE_REPO" fetch \
  --prune \
  origin \
  "+refs/heads/$DEPLOY_BRANCH:refs/remotes/origin/$DEPLOY_BRANCH"

if ! git --git-dir="$SOURCE_REPO" cat-file -e "$APP_SHA^{commit}"; then
  echo "远端仓库中不存在提交：$APP_SHA"
  exit 1
fi

if ! git --git-dir="$SOURCE_REPO" merge-base --is-ancestor \
  "$APP_SHA" "refs/remotes/origin/$DEPLOY_BRANCH"; then
  echo "拒绝部署：提交不属于 origin/$DEPLOY_BRANCH"
  exit 1
fi

cleanup_worktree() {
  git --git-dir="$SOURCE_REPO" worktree remove --force "$BUILD_DIR" \
    >/dev/null 2>&1 || true
  rm -rf "$BUILD_DIR"
  git --git-dir="$SOURCE_REPO" worktree prune >/dev/null 2>&1 || true
}
trap cleanup_worktree EXIT

cleanup_worktree

echo "检出精确提交：$APP_SHA"
git --git-dir="$SOURCE_REPO" worktree add --detach "$BUILD_DIR" "$APP_SHA"

cd "$BUILD_DIR"

if [ -x ops/ensure-mem0.sh ]; then
  if ! bash ops/ensure-mem0.sh; then
    echo "Mem0 自动准备未成功；应用将按 MEM0_REQUIRED 配置决定是否降级" >&2
  fi
fi

echo "在服务器本地编译生产 JAR"
mvn \
  --batch-mode \
  --no-transfer-progress \
  -Dmaven.repo.local="$MAVEN_CACHE" \
  -Dmaven.test.skip=true \
  clean package

BUILT_JAR="$(find target -maxdepth 1 -type f \
  -name '*.jar' \
  ! -name 'original-*.jar' \
  -print -quit)"

if [ -z "$BUILT_JAR" ] || [ ! -f "$BUILT_JAR" ]; then
  echo "服务器构建完成后没有找到可执行 JAR"
  exit 1
fi

PREVIOUS_JAR=""
if [ -L "$CURRENT_LINK" ]; then
  PREVIOUS_JAR="$(readlink -f "$CURRENT_LINK" 2>/dev/null || true)"
fi

install -m 0644 "$BUILT_JAR" "$NEW_JAR.tmp"
mv -f "$NEW_JAR.tmp" "$NEW_JAR"
ln -sfn "$NEW_JAR" "$CURRENT_LINK"

rollback() {
  if [ -n "$PREVIOUS_JAR" ] && [ -f "$PREVIOUS_JAR" ]; then
    echo "回滚到上一个版本：$PREVIOUS_JAR"
    ln -sfn "$PREVIOUS_JAR" "$CURRENT_LINK"
    sudo -n /usr/bin/systemctl restart "$SERVICE_NAME" || true
  else
    echo "没有可用的上一版本，保留失败版本供排查"
  fi
}

if ! sudo -n /usr/bin/systemctl restart "$SERVICE_NAME"; then
  echo "systemd 重启失败"
  rollback
  exit 1
fi

HEALTHY=false
for attempt in $(seq 1 20); do
  if curl --fail --silent --show-error "$HEALTH_URL" \
    | grep -q '"status":"UP"'; then
    HEALTHY=true
    break
  fi

  echo "等待应用启动：$attempt/20"
  sleep 3
done

if [ "$HEALTHY" != "true" ]; then
  echo "新版本健康检查失败"
  sudo -n /usr/bin/systemctl status "$SERVICE_NAME" --no-pager || true
  rollback
  exit 1
fi

CURRENT_RELEASE="$(readlink -f "$CURRENT_LINK")"
if [ "$CURRENT_RELEASE" != "$NEW_JAR" ] || [ ! -f "$CURRENT_RELEASE" ]; then
  echo "部署后 current.jar 指向异常"
  rollback
  exit 1
fi

# 删除旧 rsync 方案遗留的临时文件。
rm -f /tmp/person-ai-upload.jar /tmp/person-ai-*.upload.jar

# 当前版本永不参与清理；另保留四个备份版本。
find "$RELEASE_DIR" -maxdepth 1 -type f \
  -name 'person-ai-*.jar' \
  ! -path "$CURRENT_RELEASE" \
  -printf '%C@ %p\n' \
  | sort -nr \
  | tail -n +5 \
  | cut -d' ' -f2- \
  | xargs -r rm -f

if [ ! -f "$CURRENT_RELEASE" ]; then
  echo "发布清理错误：当前版本被删除"
  exit 1
fi

echo "部署成功：$APP_SHA"
echo "当前版本：$CURRENT_RELEASE"
