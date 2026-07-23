# 活动决策与状态演化链路

本文说明数字人物的活动生命周期与短期状态如何协同运行，以及当前系统已经自动化和仍需外部触发的部分。

## 1. 当前结论

活动到状态的核心闭环已经完成：

```text
外部触发一轮活动决策
        ↓
读取 Person + 持久化版本
        ↓
按经过时间结算已有状态效果
        ↓
组装活动决策上下文
        ↓
调用活动决策模型
        ↓
校验并应用 FINISH / START 命令到工作副本
        ↓
如有 START，自动评估每个新事件的状态效果
        ↓
所有步骤成功后统一提交状态、事件和效果
        ↓
以 expectedVersion 做 CAS 保存
```

该闭环具备以下性质：

- 活动决策模型只提交事件生命周期计划，不直接修改数据库。
- 状态效果模型只评估新事件的短期影响，不决定活动生命周期。
- 旧状态结算、新事件创建、效果注册和持久化属于同一应用事务语义。
- 任一模型调用、输出校验或保存失败，工作副本不会提交。
- 较慢的旧请求无法覆盖已经提交的新人物版本。

## 2. 什么会触发一轮活动决策

当前生产 HTTP 入口是：

```http
POST /api/persons/{personId}/activity-decisions
X-Internal-Token: <internal-token>
Content-Type: application/json
```

请求体可完全省略：

```bash
curl -X POST \
  -H "X-Internal-Token: $PERSON_API_TOKEN" \
  "$BASE_URL/api/persons/$PERSON_ID/activity-decisions"
```

这表示当前模式是：

> 外部触发一轮，轮内全自动。

现阶段没有后台线程根据 `nextReviewAt` 自动再次调用该接口。外部触发者可以是：

- 人工测试命令；
- 微信桥接服务；
- 后续新增的 Spring 调度器；
- 独立任务队列或调度服务；
- 其他可信内部服务。

## 3. 一轮活动决策的详细顺序

### 3.1 读取并复制人物聚合

应用从 `PersonRepository` 读取：

- 完整 `Person` 聚合；
- 当前持久化 `version`。

后续所有修改先发生在防御性工作副本上，不直接修改数据库中的已提交对象。

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

下一轮活动决策即使命令为空，系统仍会根据实际经过时间更新能量与疲劳。

这属于确定性的 Java 状态结算，不会重新询问模型“旧效果应该怎样变化”。

### 3.3 补齐尚未评估的新事件

如果数据库中存在已经开始、但尚未完成状态效果评估的事件，系统会先调用 `EventStateImpactEvaluator` 补齐效果，再继续活动决策。

正常 API 路径会尽量避免留下这种中间状态；该步骤主要用于兼容恢复和确保状态上下文完整。

### 3.4 组装活动决策上下文

活动决策模型会收到：

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
- `recentEvents`：最近已经结束的事件，默认窗口 24 小时；
- `memory`：相关长期记忆，默认最多 30 条；
- `recentConversation`：最近对话，默认最多 20 轮；
- `temporal`：人物时区下的当前时间、日期和时间段；
- `observation`：可选的外部新事实，默认空字符串。

事件快照会额外提供：

```text
owner
active
elapsedMinutes
minutesSinceEnd
```

因此即使没有 `observation`，模型也能根据活动已经持续多久、当前状态、时间、记忆和对话进行自主判断。

### 3.5 活动决策模型提交纯计划

模型必须且只能调用一次结果提交工具，输出：

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
- 结束不存在或已经结束的事件；
- 直接生成事件 ID、开始时间或结束时间；
- 直接写数据库；
- 直接产生状态变化；
- 在同一计划中对同一事件重复结束；
- 在同一渠道启动多个冲突活动。

### 3.6 Java 校验并应用生命周期命令

执行层总是先应用所有 `FINISH`，再应用所有 `START`。

同一活动渠道开始新事件时，Java 会自动把旧事件以 `REPLACED` 结束；如果旧事件自然完成或被中断，模型可先显式提交 `FINISH`。

主要渠道为：

```text
PRIMARY       STUDY、WORK、EAT、SLEEP、REST、TRAVEL、EXERCISE、SOCIAL、
              ENTERTAINMENT、SHOPPING、OTHER
COMMUNICATION CHAT
AUDIO         LISTEN_MUSIC
```

不同渠道可并行，同一渠道只能有一个当前活动。

### 3.7 新活动自动触发状态效果评估

只有计划中实际创建了新事件时，应用才会自动调用状态效果模型。

例如启动 `REST` 后，模型可能提交两个独立效果：

```text
EMOTIONAL: ENERGY +0.5
PHYSICAL:  FATIGUE -1.0
```

效果类型和允许的维度是严格映射：

| 效果类型 | 允许的状态维度 |
|---|---|
| `EMOTIONAL` | `VALENCE`、`ENERGY`、`TENSION` |
| `COGNITIVE` | `FOCUS`、`MENTAL_LOAD`、`MOTIVATION` |
| `PHYSICAL` | `FATIGUE`、`SLEEPINESS`、`HUNGER` |
| `SOCIAL` | `LONELINESS`、`SOCIAL_NEED` |

工具 Schema 会按类型限制维度。如果供应商仍返回语义违规组合，协议层会携带安全的校验原因纠错重试一次；再次失败则整轮活动决策失败，不保存半成品。

### 3.8 统一提交

当以下步骤全部成功后：

- 旧状态结算；
- 活动计划校验；
- 事件结束或创建；
- 所有新事件的效果评估；
- 状态演化上下文更新；

应用才会调用：

```text
PersonRepository.save(person, expectedVersion)
```

MySQL 更新使用版本号 CAS。受影响行数为零时返回版本冲突，拒绝覆盖更新。

## 4. observation 的正确定位

`observation` 不是每轮都必须提供的提示词，也不应把预期答案直接告诉模型。

默认自主决策应使用空 observation：

```bash
curl -X POST \
  -H "X-Internal-Token: $PERSON_API_TOKEN" \
  "$BASE_URL/api/persons/$PERSON_ID/activity-decisions"
```

只有外部系统刚刚获得、且人物事件和历史对话中尚不存在的新事实，才应传入 observation，例如：

```json
{
  "observation": "运动传感器显示人物已经停止移动。"
}
```

不推荐在正常运行中传入：

```text
跑步已经结束，人物已经回到宿舍，并开始休息。
```

因为这会把 `FINISH EXERCISE + START REST` 的结论直接写入输入，不能用于评估自主判断能力。

## 5. nextReviewMinutes 与 nextReviewAt

活动模型返回：

```text
nextReviewMinutes = 建议多久后再次判断
nextReviewAt      = decisionTime + nextReviewMinutes
```

当前它们只是本轮响应中的建议时间：

- 服务不会自动创建定时任务；
- 数据库目前不把它当作可靠调度队列；
- 应用重启后不会因为此前返回了 `nextReviewAt` 而自动恢复调用。

实现持续自主运行时，建议增加独立调度层：

```text
查找已到 nextReviewAt 的人物
        ↓
获取人物级分布式锁或租约
        ↓
调用 PersonActivityDecisionService.decide(personId, now)
        ↓
保存新的 nextReviewAt / 调度记录
        ↓
失败退避与有限重试
```

调度层必须处理：

- 同一人物不能并发运行两轮活动决策；
- 应用重启后的到期任务恢复；
- LLM 超时和供应商故障退避；
- CAS 版本冲突后重新读取，而不是盲目重试旧上下文；
- 人物停用、睡眠或静默策略；
- 最大检查频率和成本预算。

## 6. HTTP 结果解释

### commands 为空

```json
{
  "commands": [],
  "startedEvents": [],
  "finishedEvents": []
}
```

表示模型认为当前活动无需改变。状态仍可能因为已有状态效果的时间结算而变化。

### 有 START

表示应用已创建新事件，并且新事件的状态效果已经评估成功。响应中的 `activeEffects` 是提交后的当前有效效果。

### 有 FINISH

表示对应人物事件已结束。与该事件绑定的 `EVENT_END` 效果会停止继续生效，且在停止前精确结算到事件结束时间。

## 7. 失败语义

| HTTP | 稳定错误码 | 含义 |
|---|---|---|
| `400` | `INVALID_REQUEST` | 请求体、UUID 或字段非法 |
| `401` | `UNAUTHORIZED` | 内部令牌错误 |
| `404` | `PERSON_NOT_FOUND` | 人物不存在 |
| `409` | `PERSON_VERSION_CONFLICT` | 并发请求先提交，当前结果过期 |
| `409` | `PERSON_EVENT_STATE_UNSETTLED` | 事件状态尚未完成结算 |
| `502` | `ACTIVITY_DECISION_FAILED` | 活动模型没有返回可执行计划 |
| `502` | `STATE_EVALUATION_FAILED` | 状态效果模型调用或协议校验失败 |
| `500` | `INTERNAL_ERROR` | 未预期的服务端错误，需要检查日志 |

模型或解析失败时，事件、状态和效果工作副本不会保存。

## 8. 已完成与未完成

### 已完成

- 人物和用户事件时间线；
- 活动渠道并发规则；
- 实时事件开始、结束、替换和历史补录；
- 已有效果按时间自动结算；
- 活动决策模型；
- 新活动自动触发状态效果模型；
- 严格工具协议和一次纠错重试；
- MySQL 聚合持久化与乐观锁；
- 受保护的活动决策 HTTP API；
- 失败不提交半成品。

### 仍需完成

- 消费 `nextReviewAt` 的持久化调度器；
- Mem0 长期记忆真实接入；
- 最近对话持久化和微信消息同步；
- 人物启停、静默时间、成本预算和退避策略；
- 调度指标、模型调用指标和告警；
- 多实例部署时的人物级租约或分布式锁。
