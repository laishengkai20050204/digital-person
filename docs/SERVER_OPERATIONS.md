# 云服务器运行与排障手册

本文适用于当前生产部署：

```text
服务名        person-ai
应用目录      /opt/person-ai
当前 JAR      /opt/person-ai/current.jar
发布目录      /opt/person-ai/releases
环境文件      /etc/person-ai/person-ai.env
健康检查      http://127.0.0.1:8080/actuator/health
默认端口      8080
```

所有示例都不得替换为真实密钥后截图或粘贴到公共位置。

## 1. 最常用的五条命令

```bash
sudo systemctl status person-ai --no-pager -l
curl -fsS http://127.0.0.1:8080/actuator/health | jq
readlink -f /opt/person-ai/current.jar
sudo journalctl -u person-ai -n 200 --no-pager -l
sudo systemctl restart person-ai
```

它们分别用于：

1. 查看 systemd 状态；
2. 查看应用健康状态；
3. 确认实际部署的 Git 提交；
4. 查看最近日志；
5. 重启服务。

## 2. 服务状态与健康检查

### 查看服务状态

```bash
sudo systemctl status person-ai --no-pager -l
```

只看是否运行：

```bash
systemctl is-active person-ai
systemctl is-enabled person-ai
```

理想输出：

```text
active
enabled
```

### 查看健康状态

```bash
curl -fsS http://127.0.0.1:8080/actuator/health | jq
```

理想结果：

```json
{
  "status": "UP"
}
```

若服务器没有 `jq`：

```bash
sudo apt update
sudo apt install -y jq
```

或临时使用：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health \
  | python3 -m json.tool
```

### 查看监听端口

```bash
sudo ss -lntp | grep ':8080'
```

### 检查进程

```bash
PID="$(systemctl show person-ai -p MainPID --value)"
echo "PID=$PID"
ps -fp "$PID"
```

## 3. 确认当前部署版本

自动部署会把精确 Git SHA 写入 JAR 文件名：

```bash
readlink -f /opt/person-ai/current.jar
```

示例：

```text
/opt/person-ai/releases/person-ai-aff4e779d165d8ac2da110973ad9448f76bbc97a.jar
```

验证期望提交：

```bash
EXPECTED_SHA='<40位完整Git提交SHA>'

readlink -f /opt/person-ai/current.jar | grep -F "$EXPECTED_SHA" \
  && echo '目标版本已经部署' \
  || echo '目标版本尚未部署或部署失败'
```

查看保留的发布版本：

```bash
ls -lhtr /opt/person-ai/releases/person-ai-*.jar
```

部署脚本保留当前版本和最多四个旧版本。

## 4. GitHub Actions 自动部署链路

`main` 分支发生 push 后，工作流会：

```text
GitHub Actions 使用 Java 21 执行 clean verify
        ↓
通过 SSH 登录服务器 deploy 用户
        ↓
服务器从只读 bare 仓库拉取 main
        ↓
检出精确 GITHUB_SHA
        ↓
服务器使用 JDK 21 和 Maven 本地构建 JAR
        ↓
写入 /opt/person-ai/releases/person-ai-<SHA>.jar
        ↓
原子更新 current.jar 软链接
        ↓
systemctl restart person-ai
        ↓
最多检查 20 次健康状态，每次间隔 3 秒
        ↓
失败则自动恢复 previous JAR 并重启
```

部署脚本：

```text
ops/deploy-from-git.sh
```

自动部署要求服务器具备：

```text
完整 JDK 21（java + javac）
Maven
git
curl
flock
只读 GitHub Deploy Key
对 person-ai 服务的受限免密 systemctl 权限
```

## 5. 查看日志

### 最近 200 行

```bash
sudo journalctl -u person-ai -n 200 --no-pager -l
```

### 持续跟踪

```bash
sudo journalctl -u person-ai -f -o cat
```

按时间范围：

```bash
sudo journalctl \
  -u person-ai \
  --since '2026-07-23 18:50:30' \
  --until '2026-07-23 18:51:30' \
  --no-pager \
  -o cat
```

只找异常：

```bash
sudo journalctl -u person-ai -n 500 --no-pager -o cat \
  | grep -E -A 60 'Exception|Error|Caused by|failed'
```

查看本次启动以来日志：

```bash
sudo journalctl -u person-ai -b --no-pager -l
```

日志时间默认显示服务器本地时间；HTTP JSON 中的 Java `Instant` 通常使用 UTC，以 `Z` 结尾。

例如：

```text
2026-07-23T10:50:58Z = Asia/Shanghai 2026-07-23 18:50:58
```

## 6. 启动、停止与重启

```bash
sudo systemctl start person-ai
sudo systemctl stop person-ai
sudo systemctl restart person-ai
sudo systemctl reload-or-restart person-ai
```

修改 systemd unit 后：

```bash
sudo systemctl daemon-reload
sudo systemctl restart person-ai
```

查看 unit 内容：

```bash
sudo systemctl cat person-ai
```

查看 systemd 解析后的关键属性：

```bash
systemctl show person-ai \
  -p MainPID \
  -p ExecStart \
  -p EnvironmentFiles \
  -p User \
  -p Group \
  -p WorkingDirectory
```

## 7. 环境变量管理

生产环境变量保存在：

```text
/etc/person-ai/person-ai.env
```

编辑：

```bash
sudoedit /etc/person-ai/person-ai.env
```

编辑后：

```bash
sudo systemctl restart person-ai
dp-health | jq
```

### 只检查变量名是否存在，不显示值

```bash
sudo sed -nE \
  's/^[[:space:]]*(export[[:space:]]+)?([A-Z0-9_]+)=.*/\2=已设置/p' \
  /etc/person-ai/person-ai.env \
  | sort
```

检查核心开关：

```bash
sudo sed -nE \
  's/^[[:space:]]*(export[[:space:]]+)?(MYSQL_PERSISTENCE_ENABLED|PERSON_API_ENABLED|LLM_ENABLED|LLM_MODEL|LLM_CONNECTION_TEST_ENABLED|SERVER_PORT)=.*/\2=已设置/p' \
  /etc/person-ai/person-ai.env
```

### 查看正在运行进程的非敏感配置

Shell 重定向发生在 `sudo` 之前，必须使用 `sudo sh -c`：

```bash
PID="$(systemctl show person-ai -p MainPID --value)"

sudo sh -c "tr '\0' '\n' < /proc/$PID/environ" \
  | grep -E '^(MYSQL_PERSISTENCE_ENABLED|PERSON_API_ENABLED|LLM_ENABLED|LLM_MODEL|LLM_CONNECTION_TEST_ENABLED|SERVER_PORT)='
```

不得对以下变量执行无过滤输出：

```text
PERSON_API_TOKEN
LLM_API_KEY
LLM_CONNECTION_TEST_TOKEN
MYSQL_PASSWORD
```

## 8. 本地测试助手

测试助手把令牌保存在当前 Linux 用户私有目录，而不是每次手工输入。

### 私有测试环境文件

```bash
install -d -m 700 "$HOME/.config/person-ai"

sudo awk -F= '
  $1 == "PERSON_API_TOKEN" ||
  $1 == "LLM_CONNECTION_TEST_TOKEN" {
    print
  }
' /etc/person-ai/person-ai.env \
  > "$HOME/.config/person-ai/test.env"

printf '%s\n' \
  'BASE_URL=http://127.0.0.1:8080' \
  >> "$HOME/.config/person-ai/test.env"

chmod 600 "$HOME/.config/person-ai/test.env"
```

如果环境文件使用 `export KEY=value`、引号或复杂格式，可用更稳健的方式同步；下面的命令不会把值打印到终端：

```bash
install -d -m 700 "$HOME/.config/person-ai"
umask 077

{
  printf 'BASE_URL=%q\n' 'http://127.0.0.1:8080'
  printf 'PERSON_API_TOKEN=%q\n' "$(
    sudo bash -c 'set -a; source /etc/person-ai/person-ai.env; printf %s "$PERSON_API_TOKEN"'
  )"
  printf 'LLM_CONNECTION_TEST_TOKEN=%q\n' "$(
    sudo bash -c 'set -a; source /etc/person-ai/person-ai.env; printf %s "${LLM_CONNECTION_TEST_TOKEN:-}"'
  )"
} > "$HOME/.config/person-ai/test.env"

chmod 600 "$HOME/.config/person-ai/test.env"
```

### Bash 函数

加入 `~/.bashrc`：

```bash
# Digital Person test helpers
export BASE_URL=http://127.0.0.1:8080

_dp_load_test_env() {
    set -a
    . "$HOME/.config/person-ai/test.env"
    set +a
}

dp-curl() {
    _dp_load_test_env
    curl -H "X-Internal-Token: $PERSON_API_TOKEN" "$@"
}

dp-llm-test() {
    _dp_load_test_env

    if [ -z "${LLM_CONNECTION_TEST_TOKEN:-}" ]; then
        echo 'LLM_CONNECTION_TEST_TOKEN 未配置' >&2
        return 1
    fi

    curl -fsS -X POST \
      -H "X-Internal-Token: $LLM_CONNECTION_TEST_TOKEN" \
      "$BASE_URL/internal/llm/connection-test"
}

dp-health() {
    _dp_load_test_env
    curl -fsS "$BASE_URL/actuator/health"
}
```

加载：

```bash
source ~/.bashrc
```

测试：

```bash
dp-health | jq
dp-llm-test | jq
```

`BASE_URL` 需要在调用 `dp-curl "$BASE_URL/..."` 前已经存在，因为 Shell 会先展开函数实参，再进入函数体。

## 9. 人物 API 黑盒测试

### 查询人物

```bash
_dp_load_test_env
PERSON_ID='<人物UUID>'

dp-curl -fsS "$BASE_URL/api/persons/$PERSON_ID" | jq
```

### 查询状态

```bash
dp-curl -fsS "$BASE_URL/api/persons/$PERSON_ID/state" | jq
```

### 创建人物

```bash
CREATE_RESPONSE="$(
  dp-curl -fsS -X POST \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    "$BASE_URL/api/persons" <<'JSON'
{
  "identity": {
    "displayName": "测试人物",
    "birthDate": "2006-04-18",
    "genderIdentity": "女性",
    "residence": "上海",
    "timeZone": "Asia/Shanghai",
    "locale": "zh-CN",
    "roles": ["大学生"],
    "background": "服务器黑盒测试"
  },
  "personality": {
    "honestyHumility": 0.82,
    "emotionality": 0.73,
    "extraversion": 0.46,
    "agreeableness": 0.78,
    "conscientiousness": 0.68,
    "openness": 0.84
  }
}
JSON
)"

printf '%s\n' "$CREATE_RESPONSE" | jq
PERSON_ID="$(printf '%s' "$CREATE_RESPONSE" | jq -r '.personId')"
echo "PERSON_ID=$PERSON_ID"
```

## 10. 事件 API 黑盒测试

### 开始实时事件

```bash
EVENT_RESPONSE="$(
  dp-curl -fsS -X POST \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    "$BASE_URL/api/persons/$PERSON_ID/events/realtime" <<'JSON'
{
  "activityType": "EXERCISE",
  "title": "高强度跑步训练",
  "location": "学校操场",
  "participants": [],
  "notes": "正在进行高强度跑步训练。"
}
JSON
)"

printf '%s\n' "$EVENT_RESPONSE" | jq
EVENT_ID="$(printf '%s' "$EVENT_RESPONSE" | jq -r '.eventId')"
echo "EVENT_ID=$EVENT_ID"
```

### 结束事件

实际请求体和路径以当前 API 定义为准。常用路径：

```text
POST /api/persons/{personId}/events/{eventId}/finish
```

### 历史事件补录

```text
POST /api/persons/{personId}/events/history
```

历史补录只写时间线，不回放或改写当前短期状态。

## 11. 活动决策黑盒测试

### 默认自主判断：不传 observation

```bash
HTTP_CODE="$(
  dp-curl -sS \
    -o /tmp/autonomous-decision.json \
    -w '%{http_code}' \
    -X POST \
    "$BASE_URL/api/persons/$PERSON_ID/activity-decisions"
)"

echo "HTTP_CODE=$HTTP_CODE"
jq . /tmp/autonomous-decision.json
```

`commands=[]` 不代表没有执行状态更新。已有状态效果仍会按经过时间自动结算。

### 传入真实外部观察

只在外部系统获得了数据库尚未包含的新事实时使用：

```bash
HTTP_CODE="$(
  dp-curl -sS \
    -o /tmp/activity-decision.json \
    -w '%{http_code}' \
    -X POST \
    -H 'Content-Type: application/json' \
    -d '{
      "observation": "运动传感器显示人物已经停止移动。"
    }' \
    "$BASE_URL/api/persons/$PERSON_ID/activity-decisions"
)"

echo "HTTP_CODE=$HTTP_CODE"
jq . /tmp/activity-decision.json
```

不要在正常自主测试中把预期计划直接写入 observation。

## 12. 重启与持久化验证

```bash
_dp_load_test_env
PERSON_ID='<人物UUID>'

BEFORE="$(dp-curl -fsS "$BASE_URL/api/persons/$PERSON_ID")"
printf '%s\n' "$BEFORE" | jq '{personId,version,personEventCount,stateLastUpdatedAt}'

sudo systemctl restart person-ai

for attempt in $(seq 1 20); do
  if dp-health >/dev/null 2>&1; then
    break
  fi
  sleep 3
done

dp-health | jq

dp-curl -fsS "$BASE_URL/api/persons/$PERSON_ID" \
  | jq '{personId,version,personEventCount,stateLastUpdatedAt}'
```

人物重启后仍可查询，说明 MySQL 聚合持久化生效。

## 13. 常见 HTTP 故障

### 401 Unauthorized

原因：

- 本地测试令牌与服务器不一致；
- 服务器环境变量修改后没有重启；
- 请求缺少 `X-Internal-Token`。

处理：

```bash
sudo systemctl restart person-ai
# 重新安全同步 ~/.config/person-ai/test.env
```

不得通过 `echo "$PERSON_API_TOKEN"` 排查。

### 404 Not Found

可能原因：

- 请求路径错误；
- `PERSON_API_ENABLED=false`；
- 对应 Controller 因 Spring Bean 装配失败而未注册；
- 服务器尚未部署包含该接口的新版本。

先检查：

```bash
readlink -f /opt/person-ai/current.jar
sudo journalctl -u person-ai -b --no-pager -l
```

### 409 Conflict

通常是：

- 两个请求并发更新同一人物；
- 旧模型结果提交时人物版本已经变化；
- 事件状态尚未完整结算。

不要直接重复提交相同旧响应。重新读取人物后发起新决策。

### 502 Bad Gateway

稳定错误码通常为：

```text
ACTIVITY_DECISION_FAILED
STATE_EVALUATION_FAILED
```

表示模型供应商调用失败、超时、工具协议错误，或模型连续两次提交非法状态效果。

查看：

```bash
sudo journalctl -u person-ai -n 300 --no-pager -o cat \
  | grep -E -A 80 'activity decision failed|state|Exception|Caused by'
```

### 500 Internal Server Error

表示出现未映射异常。必须抓完整堆栈：

```bash
sudo journalctl -u person-ai -n 500 --no-pager -o cat
```

不要只依据 HTTP 响应猜测根因。

## 14. 模型连接排障

连接测试：

```bash
dp-llm-test | jq
```

常见结果：

| 结果 | 含义 |
|---|---|
| `200` | 模型连接和固定响应正常 |
| `401` | 连接测试令牌错误 |
| `404` | `LLM_CONNECTION_TEST_ENABLED` 未启用或接口未注册 |
| `502` | Base URL、API Key、模型名或供应商响应异常 |

检查非敏感配置：

```bash
PID="$(systemctl show person-ai -p MainPID --value)"
sudo sh -c "tr '\0' '\n' < /proc/$PID/environ" \
  | grep -E '^(LLM_ENABLED|LLM_MODEL|LLM_CONNECTION_TEST_ENABLED)='
```

查看日志：

```bash
sudo journalctl -u person-ai -n 200 --no-pager -l
```

## 15. MySQL 排障

查看 MySQL 服务：

```bash
sudo systemctl status mysql --no-pager -l
sudo systemctl is-active mysql
```

查看端口：

```bash
sudo ss -lntp | grep ':3306'
```

查看应用侧数据库异常：

```bash
sudo journalctl -u person-ai -n 300 --no-pager -o cat \
  | grep -E -A 40 'SQLException|Flyway|Hikari|MySQL|Communications link failure'
```

生产环境不应在终端命令历史中直接写数据库密码。

## 16. 手动回滚

正常自动部署在健康检查失败时会恢复上一个 JAR。只有自动回滚没有生效时才手动操作。

先列出版本：

```bash
CURRENT_JAR="$(readlink -f /opt/person-ai/current.jar)"
echo "CURRENT_JAR=$CURRENT_JAR"
ls -lhtr /opt/person-ai/releases/person-ai-*.jar
```

人工选择确认过的旧 JAR：

```bash
ROLLBACK_JAR='/opt/person-ai/releases/person-ai-<旧提交SHA>.jar'

test -f "$ROLLBACK_JAR" || {
  echo '回滚文件不存在' >&2
  exit 1
}

sudo ln -sfn "$ROLLBACK_JAR" /opt/person-ai/current.jar
sudo systemctl restart person-ai
curl -fsS http://127.0.0.1:8080/actuator/health | jq
readlink -f /opt/person-ai/current.jar
```

回滚后应立即在 GitHub 中修复或回退 `main`，否则下一次自动部署仍可能重新部署故障版本。

## 17. 磁盘与资源检查

```bash
df -h
free -h
uptime
sudo du -sh /opt/person-ai/*
```

查看 Java 进程资源：

```bash
PID="$(systemctl show person-ai -p MainPID --value)"
ps -p "$PID" -o pid,ppid,%cpu,%mem,rss,vsz,etime,cmd
```

查看最近 OOM：

```bash
sudo journalctl -k -n 300 --no-pager \
  | grep -Ei 'out of memory|oom|killed process'
```

## 18. 安全要求

- 不在聊天、Issue、PR、截图或日志中粘贴真实令牌和密码。
- `/etc/person-ai/person-ai.env` 只允许必要用户读取。
- `~/.config/person-ai/test.env` 权限必须为 `600`。
- GitHub Deploy Key 使用只读权限。
- GitHub Actions SSH 私钥只保存在仓库 Secrets。
- 服务器防火墙无需向公网开放 `8080`；内部接口默认绑定或访问 `127.0.0.1`。
- 连接测试接口只在需要时启用。
- 日志不记录提示词、聊天正文、记忆正文、工具参数或密钥。

检查权限：

```bash
sudo stat -c '%a %U:%G %n' /etc/person-ai/person-ai.env
stat -c '%a %U:%G %n' "$HOME/.config/person-ai/test.env"
```

## 19. 推荐的日常检查顺序

```text
1. systemctl is-active person-ai
2. dp-health | jq
3. readlink -f /opt/person-ai/current.jar
4. 执行一个只读人物查询
5. 查看最近 WARN / ERROR 日志
6. 只有出现异常时再重启
```

不要把“重启服务”作为第一排障动作。先保存日志和当前版本信息，否则可能丢失最有价值的现场上下文。
