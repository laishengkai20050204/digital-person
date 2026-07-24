# 持久化活动调度器

本文说明数字人物如何依据 `nextReviewAt` 持续自动运行，以及生产服务器如何安全启用、观察和停用调度器。

## 1. 当前调用链

```text
person_activity_schedule.next_review_at 到期
        ↓
调度器条件抢占数据库租约
        ↓
启动租约续期心跳
        ↓
PersonActivityDecisionService 执行一轮活动决策
        ↓
结算旧状态效果
        ↓
调用活动决策模型
        ↓
应用 FINISH / START
        ↓
新活动自动评估状态效果
        ↓
在整体 deadline 前 CAS 保存人物聚合
        ↓
保存新的 next_review_at 并释放租约
```

任务信息保存在 MySQL 中，因此应用重启、自动部署或云服务器重启后不会丢失。

## 2. 默认安全状态

调度器默认关闭：

```text
ACTIVITY_SCHEDULER_ENABLED=false
```

启用时还要求：

```text
MYSQL_PERSISTENCE_ENABLED=true
LLM_ENABLED=true
```

代码部署本身不会自动产生后台模型费用，只有生产环境显式启用后才会运行。

## 3. 调度表

Flyway 创建表：

```text
person_activity_schedule
├── person_id
├── enabled
├── next_review_at
├── lease_token
├── lease_until
├── failure_count
├── last_error_type
├── last_started_at
├── last_completed_at
├── created_at
└── updated_at
```

`person_id` 同时是主键和外键。人物删除时，对应调度记录自动删除。

## 4. 首次计划

调度器会为尚无调度记录的已持久化人物补建首轮计划：

```text
next_review_at = 当前时间 + initial-review-delay
```

默认：

```text
initial-review-delay = 1 分钟
```

创建人物时已经在同一数据库事务中同步建立首轮计划；轮询仍保留缺失记录补齐作为修复性 reconciliation。人物规模扩大后，应把该兜底扫描降为低频任务。

## 5. 数据库租约

每次执行前，实例生成随机 `lease_token`，并通过条件更新领取任务：

```text
任务已经到期
并且
当前没有租约，或旧租约已经过期
```

只有条件更新成功的实例才能执行该人物。

默认租约：

```text
lease-duration = 10 分钟
```

### 主动续租

长任务运行期间，每隔一段时间使用同一个 `person_id + lease_token` 延长租约：

```text
lease-renewal-interval = 2 分钟
```

续租 SQL 还要求旧租约尚未过期。以下情况续租会失败：

- 任务已被释放；
- 租约已过期；
- 其他实例已经获得新 token；
- 人物调度已被停用。

一旦心跳确认租约不再属于当前任务，当前实例不会再写入调度完成结果。

## 6. 整体活动决策 deadline

调度器为每轮自动决策传入整体 deadline：

```text
decision-timeout = 8 分钟
```

默认关系为：

```text
单次模型超时 60 秒
< 整轮活动决策 deadline 8 分钟
< 数据库租约 10 分钟
```

活动服务会在关键阶段检查 deadline，包括：

- 人物加载；
- 上下文组装；
- 活动模型调用前后；
- 新活动状态效果评估前后；
- 聚合提交；
- Repository 保存前。

如果模型结果在 deadline 后才返回，本轮不会保存人物聚合，而是进入调度失败退避。

该 deadline 是“禁止迟到结果提交”的业务保护，并不会强行中断已经发出的网络 socket；单次供应商调用仍由 `LLM_TIMEOUT` 控制。

## 7. 全局模型并发闸门

所有 `LanguageModelGateway` 调用都会经过同一个进程级并发闸门，包括：

- 自动活动决策；
- 状态效果评估；
- 手动 API；
- Agent 工具循环；
- 诊断和连接测试。

默认：

```text
LLM_CONCURRENCY_MAXIMUM=4
LLM_CONCURRENCY_ACQUIRE_TIMEOUT=2s
```

达到并发上限后，请求只等待有限时间；仍无法获得容量时返回 `LanguageModelException`，不会无限创建虚拟线程或无限排队。

调度器自己的 `max-in-flight` 只限制活动决策轮数；全局 LLM 闸门负责限制所有入口的模型调用总量。

## 8. 成功流程

成功后：

1. 人物聚合通过版本号 CAS 保存；
2. 使用模型返回的 `nextReviewAt` 续排；
3. 若模型调用过慢，至少再等待 `minimum-review-delay`；
4. 清空 `lease_token` 和 `lease_until`；
5. `failure_count` 归零；
6. 清空 `last_error_type`。

默认最短复查间隔：

```text
minimum-review-delay = 1 分钟
```

## 9. 失败与退避

### 乐观锁冲突

微信消息、人工事件命令或其他请求先修改人物时，会产生 `PersonVersionConflictException`。

处理方式：

- 不增加 `failure_count`；
- 默认 30 秒后重新读取最新人物；
- 不重放旧模型结果。

### 模型、协议、deadline 或其他失败

使用指数退避：

```text
第 1 次：5 分钟
第 2 次：10 分钟
第 3 次：20 分钟
第 4 次：40 分钟
以后最多：60 分钟
```

数据库只保存异常类型，不保存提示词、人物记忆、聊天正文、模型回复或密钥。

## 10. 生产配置

编辑：

```bash
sudoedit /etc/person-ai/person-ai.env
```

推荐的单人物/小规模配置：

```bash
ACTIVITY_SCHEDULER_ENABLED=true
ACTIVITY_SCHEDULER_POLL_INTERVAL=10s
ACTIVITY_SCHEDULER_INITIAL_DELAY=30s
ACTIVITY_SCHEDULER_INITIAL_REVIEW_DELAY=1m
ACTIVITY_SCHEDULER_MINIMUM_REVIEW_DELAY=1m
ACTIVITY_SCHEDULER_CONFLICT_RETRY_DELAY=30s

ACTIVITY_SCHEDULER_LEASE_DURATION=10m
ACTIVITY_SCHEDULER_LEASE_RENEWAL_INTERVAL=2m
ACTIVITY_SCHEDULER_DECISION_TIMEOUT=8m

ACTIVITY_SCHEDULER_FAILURE_BACKOFF=5m
ACTIVITY_SCHEDULER_MAX_FAILURE_BACKOFF=1h
ACTIVITY_SCHEDULER_BATCH_SIZE=1
ACTIVITY_SCHEDULER_MAX_IN_FLIGHT=1

LLM_CONCURRENCY_MAXIMUM=4
LLM_CONCURRENCY_ACQUIRE_TIMEOUT=2s
```

确认：

```bash
MYSQL_PERSISTENCE_ENABLED=true
LLM_ENABLED=true
```

重启并检查：

```bash
sudo systemctl restart person-ai
curl -fsS http://127.0.0.1:8080/actuator/health | jq
sudo systemctl status person-ai --no-pager -l
```

配置约束：

```text
lease-renewal-interval < lease-duration
decision-timeout < lease-duration
max-failure-backoff >= failure-backoff
```

不满足时应用会在启动阶段失败，避免以危险参数静默运行。

## 11. 查看调度日志

```bash
sudo journalctl -u person-ai -f -o cat \
  | grep --line-buffered -E \
    'persistent activity|Persistent activity|scheduled activity|Scheduled activity|activity schedule lease|concurrency limit|deadline'
```

关键日志：

```text
Initialized persistent activity schedules
Starting scheduled activity decision
Completed scheduled activity decision
Scheduled activity decision failed
Rescheduled activity decision after version conflict
Renewed activity schedule lease
Activity schedule lease heartbeat lost ownership
Language model concurrency limit reached
```

正常任务少于 2 分钟时不会出现续租日志，这是正常现象；心跳只在达到续租间隔后执行。

## 12. 查看调度表

不要假定 MySQL root 可以无密码登录。应使用 `/etc/person-ai/person-ai.env` 中的应用账号。

```bash
sudo bash <<'EOF'
set -a
source /etc/person-ai/person-ai.env
set +a

url="${MYSQL_JDBC_URL#jdbc:mysql://}"
authority="${url%%/*}"
db_and_query="${url#*/}"
database="${db_and_query%%\?*}"
host="${authority%%:*}"

if [[ "$authority" == *:* ]]; then
  port="${authority##*:}"
else
  port=3306
fi

MYSQL_PWD="$MYSQL_PASSWORD" mysql \
  --protocol=TCP \
  -h "$host" \
  -P "$port" \
  -u "$MYSQL_USERNAME" \
  "$database" \
  -e "
SELECT
  person_id,
  enabled,
  next_review_at,
  lease_until,
  failure_count,
  last_error_type,
  last_started_at,
  last_completed_at
FROM person_activity_schedule
ORDER BY person_id;
"
EOF
```

健康记录通常满足：

```text
enabled = 1
lease_until = NULL（任务空闲时）
failure_count = 0
last_error_type = NULL
next_review_at 在未来
```

## 13. 暂停与恢复单个人物

暂停：

```sql
UPDATE person_activity_schedule
SET enabled = FALSE,
    lease_token = NULL,
    lease_until = NULL
WHERE person_id = '<人物UUID>';
```

恢复并立即到期：

```sql
UPDATE person_activity_schedule
SET enabled = TRUE,
    next_review_at = CURRENT_TIMESTAMP(6)
WHERE person_id = '<人物UUID>';
```

停用整个调度器：

```bash
ACTIVITY_SCHEDULER_ENABLED=false
sudo systemctl restart person-ai
```

调度表不会被删除，以后重新启用可以继续恢复。

## 14. 手动 API

接口仍保留：

```http
POST /api/persons/{personId}/activity-decisions
```

它用于黑盒测试、诊断和临时人工触发。手动调用不会主动改写调度表的 `next_review_at`，并可能与自动任务发生人物版本冲突。

## 15. 当前边界

已经实现：

- MySQL 持久化任务；
- 重启恢复；
- 单人物租约；
- 多实例条件抢占；
- 租约续期心跳；
- 丢失租约后禁止调度结果写回；
- 整轮活动决策 deadline；
- 人物 CAS 保存；
- 成功续排；
- 版本冲突重读；
- 指数失败退避；
- 调度并发限制；
- 全局 LLM 并发闸门。

仍未实现：

- 低频 reconciliation 取代每轮全表补齐；
- 调度管理 HTTP API；
- Prometheus 指标和告警；
- 按人物成本预算；
- 按前台、调度、诊断划分独立模型并发配额；
- 验证并强化底层供应商对取消信号的实际响应。
