# 活动决策与状态演化链路

本文说明数字人物的活动生命周期、短期状态和持久化调度器如何协同运行。

## 1. 当前结论

活动到状态、再到下一轮活动判断的闭环已经完成：

```text
MySQL 中的 nextReviewAt 到期
        ↓
调度器抢占人物级租约
        ↓
读取 Person + 持久化版本
        ↓
按经过时间结算已有状态效果
        ↓
组装活动决策上下文
        ↓
调用活动决策模型
        ↓
校验并应用 FINISH / START 到工作副本
        ↓
如有 START，自动评估新事件状态效果
        ↓
所有步骤成功后 CAS 保存人物聚合
        ↓
保存新的 nextReviewAt 并释放租约
```

调度器默认关闭。未设置 `ACTIVITY_SCHEDULER_ENABLED=true` 时，仍可通过受保护 HTTP API 人工触发一轮。

## 2. 两种触发方式

### 持久化自动调度

生产持续运行入口是 MySQL 调度表：

```text
person_activity_schedule.next_review_at
```

Spring 每隔一段时间扫描到期记录，获取租约后直接调用：

```text
PersonActivityDecisionService.decide(personId, decisionTime)
```

这种方式会在成功后自动保存下一次检查时间。

### 人工诊断接口

```http
POST /api/persons/{personId}/activity-decisions
X-Internal-Token: <internal-token>
```

请求体可省略：

```bash
curl -X POST \
  -H "X-Internal-Token: $PERSON_API_TOKEN" \
  "$BASE_URL/api/persons/$PERSON_ID/activity-decisions"
```

该接口保留用于黑盒测试和故障诊断。当前人工调用返回的 `nextReviewAt` 不主动覆盖持久化调度表。

## 3. 一轮决策的详细顺序

### 3.1 读取并复制人物聚合

应用从 `PersonRepository` 读取：

- 完整 `Person` 聚合；
- 当前持久化 `version`。

后续修改发生在防御性工作副本上，不直接修改已提交对象。

### 3.2 自动结算已有状态效果

`StateUpdater.prepare(...)` 根据以下数据结算到当前决策时间：

- `lastUpdatedAt`；
- 当前仍有效的状态效果；
- 效果的指数变化速率 `shape`；
- 事件的真实结束时间；
- 当前仍在进行的事件。

例如休息事件具有：

```text
ENERGY shape = +0.5
FATIGUE shape = -1.0
```

下一轮即使命令为空，系统仍会根据实际经过时间更新能量与疲劳。这是确定性的 Java 计算，不会重新询问模型旧效果如何变化。

### 3.3 补齐未评估事件

如果存在已经开始、但尚未完成状态效果评估的事件，系统会先调用 `EventStateImpactEvaluator` 补齐效果，再进行活动判断。

正常事件命令路径会尽量避免留下这种中间状态；该步骤用于恢复和一致性保护。

### 3.4 组装活动决策上下文

活动模型收到：

```text
personId
identity
personality
currentState
activeEffects
activeEvents
recentEvents
memory
recentConversation
observation
temporal
evaluationTime
```

其中：

- `activeEvents`：当前仍在进行的人物或用户事件；
- `recentEvents`：最近已结束事件，默认窗口 24 小时；
- `memory`：相关长期记忆，默认最多 30 条；
- `recentConversation`：最近对话，默认最多 20 轮；
- `temporal`：人物时区下的当前时间、日期和时间段；
- `observation`：可选外部新事实，自动调度时为空字符串。

事件快照还提供：

```text
owner
active
elapsedMinutes
minutesSinceEnd
```

所以没有 observation 时，模型仍能根据活动持续时间、状态、当地时间、记忆和对话自主判断。

### 3.5 活动模型提交纯计划

模型必须调用一次结果提交工具，返回：

```json
{
  "commands": [],
  "nextReviewMinutes": 15
}
```

或：

```json
{
  "commands": [
    {
      "action": "FINISH",
      "eventId": "...",
      "reason": "COMPLETED"
    },
    {
      "action": "START",
      "activityType": "REST",
      "title": "跑步后休息",
      "location": "宿舍",
      "participants": [],
      "notes": "高强度跑步后休息"
    }
  ],
  "nextReviewMinutes": 30
}
```

模型不能：

- 修改用户事件；
- 结束不存在或已结束的事件；
- 直接生成事件 ID、开始时间或结束时间；
- 直接写数据库；
- 直接修改状态；
- 在同一计划中重复结束同一事件；
- 在同一渠道启动多个冲突活动。

### 3.6 Java 应用生命周期命令

执行层总是先应用所有 `FINISH`，再应用所有 `START`。

同一渠道启动新事件时，Java 自动把旧事件以 `REPLACED` 结束。不同渠道可以并行：

```text
PRIMARY       STUDY、WORK、EAT、SLEEP、REST、TRAVEL、EXERCISE、SOCIAL、
              ENTERTAINMENT、SHOPPING、OTHER
COMMUNICATION CHAT
AUDIO         LISTEN_MUSIC
```

### 3.7 新活动自动触发状态效果评估

只有实际创建新事件时，应用才调用状态效果模型。

例如启动 `REST` 后，模型可能提交：

```text
EMOTIONAL: ENERGY +0.5
PHYSICAL:  FATIGUE -1.0
```

允许维度严格映射：

| 效果类型 | 允许维度 |
|---|---|
| `EMOTIONAL` | `VALENCE`、`ENERGY`、`TENSION` |
| `COGNITIVE` | `FOCUS`、`MENTAL_LOAD`、`MOTIVATION` |
| `PHYSICAL` | `FATIGUE`、`SLEEPINESS`、`HUNGER` |
| `SOCIAL` | `LONELINESS`、`SOCIAL_NEED` |

工具 Schema 按类型限制维度。模型仍提交非法组合时，协议层会携带安全校验原因纠错重试一次；再次失败则整轮失败，不保存半成品。

### 3.8 统一保存

以下步骤全部成功后才调用：

```text
PersonRepository.save(person, expectedVersion)
```

MySQL 使用版本号 CAS。受影响行数为零时返回版本冲突，旧结果无法覆盖新人物状态。

## 4. observation 的正确定位

自动调度使用空 observation。

只有外部系统刚获得、且事件和对话中尚不存在的新事实，才应传入，例如：

```json
{
  "observation": "运动传感器显示人物已经停止移动。"
}
```

不应在正常自主测试中直接写入结论：

```text
跑步已经结束，人物已经回到宿舍并开始休息。
```

这种输入会直接暗示 `FINISH EXERCISE + START REST`，不能检验自主判断能力。

## 5. commands 为空为什么仍会保存

```json
{
  "commands": [],
  "startedEvents": [],
  "finishedEvents": []
}
```

只表示当前活动无需改变。已有状态效果仍会按时间结算，因此：

- `state` 可能变化；
- `stateLastUpdatedAt` 会更新；
- 人物版本可能增加；
- 调度器会保存新的 `nextReviewAt`。

## 6. 持久化调度语义

自动决策成功后：

```text
nextReviewAt = decisionTime + nextReviewMinutes
```

调度器会把它保存到 `person_activity_schedule`。

如果调用耗时较长，模型时间已经接近完成时间，调度器会保证至少等待 `minimum-review-delay`，避免立即循环。

应用重启后，MySQL 中的到期时间仍存在。旧租约过期后，新实例可以重新抢占任务。

完整配置和服务器命令见 [持久化活动调度器](./ACTIVITY_SCHEDULER.md)。

## 7. 并发与失败语义

### 人物聚合并发

人物写入由 `expectedVersion` CAS 保护。并发请求只允许一个提交。

### 调度并发

调度表使用随机租约令牌和过期时间。同一人物在有效租约期间不会被正常抢占两次。

### 版本冲突

版本冲突通常表示微信消息或人工事件刚修改了人物。调度器不增加失败次数，短暂等待后重新读取。

### 模型失败

模型调用、工具协议或状态效果评估失败时，调度器增加连续失败次数，并使用有上限的指数退避。

## 8. HTTP 错误

| HTTP | 稳定错误码 | 含义 |
|---|---|---|
| `400` | `INVALID_REQUEST` | 请求体、UUID 或字段非法 |
| `401` | `UNAUTHORIZED` | 内部令牌错误 |
| `404` | `PERSON_NOT_FOUND` | 人物不存在 |
| `409` | `PERSON_VERSION_CONFLICT` | 并发请求先提交，当前结果过期 |
| `409` | `PERSON_EVENT_STATE_UNSETTLED` | 事件状态尚未完成结算 |
| `502` | `ACTIVITY_DECISION_FAILED` | 活动模型没有返回可执行计划 |
| `502` | `STATE_EVALUATION_FAILED` | 状态效果模型调用或协议校验失败 |
| `500` | `INTERNAL_ERROR` | 未预期错误，需要检查日志 |

自动调度失败不会产生 HTTP 响应，而是记录结构化日志并更新调度表中的失败类型和重试时间。

## 9. 已完成与后续边界

已完成：

- 活动与状态闭环；
- `nextReviewAt` 持久化；
- 自动到期扫描；
- 单人物数据库租约；
- 应用重启恢复；
- 多实例条件抢占；
- 冲突重试；
- 失败退避；
- 进程级并发上限；
- 默认关闭避免意外费用。

后续仍可增加：

- 调度管理 HTTP API；
- Prometheus 指标和告警；
- 租约续租心跳；
- 每个人物独立预算和暂停策略；
- 微信端展示当前活动和下一次检查时间；
- Mem0 与真实聊天历史接入。