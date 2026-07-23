# 持久化活动调度器

本文说明数字人物如何依据 `nextReviewAt` 持续自动运行，以及生产服务器如何安全启用、观察和停用调度器。

## 1. 当前能力

持久化调度器把原来的：

```text
人工调用一次 activity-decisions
```

扩展为：

```text
数据库保存 nextReviewAt
        ↓
Spring 定期扫描到期人物
        ↓
获取人物级数据库租约
        ↓
自动执行一轮活动决策
        ↓
保存新的 nextReviewAt
        ↓
等待下一轮
```

应用重启、自动部署或云服务器重启后，调度记录仍保存在 MySQL 中，不依赖 JVM 内存定时任务。

## 2. 默认安全状态

调度器默认关闭：

```text
ACTIVITY_SCHEDULER_ENABLED=false
```

因此代码合并和自动部署不会立即产生模型调用或费用。只有生产环境显式设置为 `true` 并重启服务后才开始运行。

启用调度器还要求：

```text
MYSQL_PERSISTENCE_ENABLED=true
LLM_ENABLED=true
```

缺少数据库或活动决策模型依赖时，应用会在启动阶段明确失败，而不是静默降级成一个不工作的调度器。

## 3. 数据库结构

Flyway V2 创建：

```text
person_activity_schedule
├── person_id           人物 UUID，主键并外键关联 digital_person
├── enabled             是否参与自动调度
├── next_review_at      下一次到期时间
├── lease_token         当前租约令牌
├── lease_until         当前租约过期时间
├── failure_count       连续失败次数
├── last_error_type     最近失败类型，不保存异常正文
├── last_started_at     最近一次自动决策开始时间
├── last_completed_at   最近一次自动决策完成时间
├── created_at
└── updated_at
```

人物删除时，其调度记录通过外键自动删除。

## 4. 首次启用行为

轮询器发现 `digital_person` 中存在人物、但没有对应调度记录时，会自动插入首轮计划：

```text
next_review_at = 当前时间 + initial-review-delay
```

默认：

```text
initial-review-delay = 1 分钟
```

这意味着首次开启后，已有测试人物会在约 1 分钟后执行第一轮自动活动决策。

## 5. 数据库租约

每次执行前，实例会生成随机 `lease_token`，并通过条件更新抢占任务：

```text
任务已经到期
并且
当前没有租约，或旧租约已经过期
```

只有条件更新成功的实例才能执行人物决策。

租约的作用：

- 防止同一个 Java 实例重复执行同一人物；
- 防止以后多个应用实例同时执行同一人物；
- 应用崩溃后，租约到期即可由新实例恢复；
- 不需要依赖进程内锁保存任务状态。

默认租约时长：

```text
10 分钟
```

它应当明显长于一次活动模型调用和新活动状态效果评估的最坏耗时。

## 6. 成功流程

```text
抢到租约
  ↓
PersonActivityDecisionService.decide(personId, claimedAt)
  ↓
结算旧状态效果
  ↓
调用活动决策模型
  ↓
应用 FINISH / START
  ↓
如有 START，评估新活动状态效果
  ↓
人物聚合 CAS 保存成功
  ↓
更新调度表 next_review_at
  ↓
清除租约、failure_count 归零
```

模型返回的 `nextReviewAt` 会被持久化。

如果模型建议时间已经因为模型调用过慢而落在完成时间附近，调度器会保证至少再等待：

```text
minimum-review-delay
```

默认 1 分钟，避免立即形成高频循环。

## 7. 失败与退避

### 乐观锁冲突

如果微信消息、人工事件命令或另一个请求先修改了人物，活动决策会遇到 `PersonVersionConflictException`。

这种情况不代表模型故障，因此：

- 不增加 `failure_count`；
- 默认 30 秒后重新读取最新人物再判断；
- 不重放旧模型结果。

### 模型或协议失败

其他失败会使用指数退避：

```text
第 1 次失败：5 分钟
第 2 次失败：10 分钟
第 3 次失败：20 分钟
第 4 次失败：40 分钟
以后最多：60 分钟
```

只保存异常类型，例如：

```text
LanguageModelException
StateTransitionEvaluationException
```

不会把提示词、聊天正文、人物记忆、模型回复或 API Key 写入调度表。

## 8. 并发与成本限制

默认：

```text
batch-size   = 10
max-in-flight = 4
poll-interval = 10 秒
```

含义：

- 一次轮询最多尝试抢占 10 个人物；
- 单个应用进程最多同时运行 4 轮模型决策；
- 每 10 秒扫描一次到期任务；
- 未到期人物不会调用模型。

当前只有一个人物时，可先使用更保守的生产配置：

```text
ACTIVITY_SCHEDULER_BATCH_SIZE=1
ACTIVITY_SCHEDULER_MAX_IN_FLIGHT=1
```

## 9. 生产启用

编辑环境文件：

```bash
sudoedit /etc/person-ai/person-ai.env
```

增加：

```bash
ACTIVITY_SCHEDULER_ENABLED=true
ACTIVITY_SCHEDULER_POLL_INTERVAL=10s
ACTIVITY_SCHEDULER_INITIAL_DELAY=30s
ACTIVITY_SCHEDULER_INITIAL_REVIEW_DELAY=1m
ACTIVITY_SCHEDULER_MINIMUM_REVIEW_DELAY=1m
ACTIVITY_SCHEDULER_CONFLICT_RETRY_DELAY=30s
ACTIVITY_SCHEDULER_LEASE_DURATION=10m
ACTIVITY_SCHEDULER_FAILURE_BACKOFF=5m
ACTIVITY_SCHEDULER_MAX_FAILURE_BACKOFF=1h
ACTIVITY_SCHEDULER_BATCH_SIZE=1
ACTIVITY_SCHEDULER_MAX_IN_FLIGHT=1
```

确认这些已有开关仍为：

```bash
MYSQL_PERSISTENCE_ENABLED=true
LLM_ENABLED=true
```

重启：

```bash
sudo systemctl restart person-ai
curl -fsS http://127.0.0.1:8080/actuator/health | jq
```

## 10. 验证 Flyway 迁移

查看启动日志：

```bash
sudo journalctl -u person-ai -b --no-pager -o cat \
  | grep -E 'Flyway|person_activity_schedule|Migration|migrat'
```

本机 MySQL 允许 root socket 登录时，可直接检查：

```bash
sudo mysql digital_person -e \
  "SHOW TABLES LIKE 'person_activity_schedule';"
```

查看调度记录：

```bash
sudo mysql digital_person -e "
SELECT
  person_id,
  enabled,
  next_review_at,
  lease_until,
  failure_count,
  last_error_type,
  last_started_at,
  last_completed_at
FROM person_activity_schedule;
"
```

命令不会显示数据库密码、API Key 或人物 JSON。

## 11. 观察自动运行日志

持续观察：

```bash
sudo journalctl -u person-ai -f -o cat \
  | grep --line-buffered -E \
    'persistent activity|Persistent activity|scheduled activity|Scheduled activity'
```

关键日志包括：

```text
Initialized persistent activity schedules
Starting scheduled activity decision
Completed scheduled activity decision
Scheduled activity decision failed
Rescheduled activity decision after version conflict
```

日志只包含人物 ID、次数、时间、命令数量和异常类型。

## 12. 验证人物确实自动变化

启用前记录当前版本：

```bash
_dp_load_test_env
PERSON_ID='<人物UUID>'

dp-curl -fsS "$BASE_URL/api/persons/$PERSON_ID" \
  | jq '{version,stateLastUpdatedAt,personEventCount,state,activeEffects}'
```

等待超过 `initial-review-delay`，再次执行同一查询。

若自动决策成功，通常可以观察到：

- `version` 增加；
- `stateLastUpdatedAt` 更新；
- 状态随已有效果继续演化；
- 模型需要改变活动时，事件数量或当前效果发生变化。

即使模型返回 `commands=[]`，状态结算仍会保存，因此版本和状态更新时间可能变化。

## 13. 停用

编辑：

```bash
sudoedit /etc/person-ai/person-ai.env
```

设置：

```bash
ACTIVITY_SCHEDULER_ENABLED=false
```

然后：

```bash
sudo systemctl restart person-ai
```

数据库中的 `next_review_at` 和失败记录会保留。以后重新启用时，可以继续从持久化任务恢复。

如需只暂停某一个人物，可以直接设置：

```bash
sudo mysql digital_person -e "
UPDATE person_activity_schedule
SET enabled = FALSE
WHERE person_id = '<人物UUID>';
"
```

恢复：

```bash
sudo mysql digital_person -e "
UPDATE person_activity_schedule
SET enabled = TRUE,
    next_review_at = CURRENT_TIMESTAMP(6)
WHERE person_id = '<人物UUID>';
"
```

## 14. 手动 API 的定位

以下接口仍然保留：

```http
POST /api/persons/{personId}/activity-decisions
```

它用于：

- 黑盒测试；
- 故障诊断；
- 临时人工触发。

当前手动调用返回的 `nextReviewAt` 不会主动覆盖调度表中的计划。启用自动调度后，正常运行应由调度器触发；进行人工测试时要注意可能与自动任务发生 CAS 冲突，冲突任务会在 30 秒后重新读取人物。

## 15. 当前边界

当前版本已经解决：

- 重启后任务恢复；
- 单人物互斥租约；
- 多实例抢占安全；
- 成功续排；
- 冲突重读；
- 失败退避；
- 并发和费用上限。

尚未实现：

- 调度管理 HTTP API；
- 租约执行中的主动续租心跳；
- Prometheus 调度指标与告警；
- 按人物设置独立成本预算；
- 微信侧展示下一次活动判断时间。

在当前单服务器、少量人物的部署规模下，10 分钟租约和 MySQL 持久化队列已经能够提供稳定的第一版持续自主运行能力。