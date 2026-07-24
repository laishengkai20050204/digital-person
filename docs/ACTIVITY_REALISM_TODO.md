# 活动现实性演化设计

本文记录活动现实性第五批的已实现能力、边界和后续观测项。

## 当前已完成

### 1. Java 自然状态演化

每次状态结算时，Java 会先按照实际经过时间执行永久自然调节，再结算模型产生的短期事件效果。自然调节不保存为普通 `RegisteredStateEffect`，因为它的方向和目标会随活动、当地时间和生活历史动态变化。

当前自然调节覆盖：

- 距离上次进食越久，`HUNGER` 越接近较高目标；
- 正在进食时，`HUNGER` 快速向低目标移动；
- 当地昼夜节律、持续清醒时间和最近 24 小时睡眠债共同决定 `SLEEPINESS`；
- 睡眠恢复 `ENERGY`，降低 `FATIGUE`、`SLEEPINESS` 和 `MENTAL_LOAD`；
- 学习、工作、运动和休息使用不同的能量与疲劳目标；
- 不在学习或工作时，`FOCUS`、`MOTIVATION`、`MENTAL_LOAD` 缓慢回归基础水平。

自然演化按 15 分钟分片计算，因此跨越用餐、睡眠或昼夜时间段时会使用对应区间的规则，而不要求每 15 分钟写数据库。

### 2. 目标值指数模型

原有事件效果仍使用带符号 `shape` 向维度上下界移动。`StateTransitionModel` 同时支持向任意目标值移动：

```text
next = target + (current - target) × exp(-rate × elapsedHours)
```

自然调节复用该数学模型，不新增另一套 transition 类型。

### 3. 模型效果强度档位

状态效果模型不再直接提交任意数值 `shape`，而是提交：

```text
direction = INCREASE | DECREASE
intensity = LOW | MEDIUM | HIGH | EXTREME | INSTANT
```

Java 映射范围：

```text
LOW      0.08～0.12 / 小时
MEDIUM   0.20～0.30 / 小时
HIGH     0.40～0.60 / 小时
EXTREME  0.80～1.20 / 小时
INSTANT  24.0～36.0 / 小时
```

映射使用人物、事件、维度、方向和档位组成的稳定种子。同一次事件重试或服务重启后得到相同数值，不同事件保留轻微差异。

生命周期限制：

- `EVENT_END` 只允许 `LOW` 或 `MEDIUM`；
- `HIGH` 固定效果最多 180 分钟；
- `EXTREME` 最多 60 分钟；
- `INSTANT` 只能使用 `FIXED_TIME`，最多 10 分钟。

普通睡眠恢复、自然饥饿、进食和认知基线回归由 Java 负责，模型不得重复注册这些基础变化。

### 4. Java 活动持续时长等级

每个事件快照新增：

```text
durationStatus = NORMAL | EXTENDED | SEVERELY_EXTENDED | STALE
```

阈值按 `ActivityType` 区分。例如学习/工作、进食、睡眠、运动和休息使用不同范围。模型仍能看到原始 `elapsedMinutes`，等级只是确定性现实性信号，不直接替模型结束活动。

### 5. 活动生理上下文

活动决策上下文新增 `physiology`：

```text
activePrimaryActivityType
activePrimaryElapsedMinutes
activePrimaryDurationStatus
sleepMinutesLast24Hours
sleepDebtMinutes
awakeMinutes
minutesSinceLastMeal
```

睡眠判断必须综合已睡时长、最近 24 小时睡眠量、睡眠债、困倦、疲劳和能量。当前没有加入“睡眠不足六小时禁止醒来”的 Java 硬规则，先观察自然状态和提示词是否足以约束模型。

## 当前验收重点

部署后重点观察完整一天轨迹：

- 4 小时睡眠不应恢复到接近满能量和零疲劳；
- 8 小时睡眠应比 4 小时恢复更多；
- 早餐期间饥饿应下降，早餐结束后不应继续保留普通空腹增益效果；
- 深夜和长时间清醒后困倦应自然上升；
- 学习结束后专注、动机和认知负荷应逐渐回归；
- 模型效果重试时数值必须稳定；
- `INSTANT` 只能用于真正短暂、突发的效果。

## 暂缓项

以下项目根据实测再决定：

1. 睡眠不足指定时长时的 Java 强制阻止醒来；
2. 将自然参数迁移为外部配置；
3. 持久化更长周期的睡眠债，而不只从最近事件时间线推导；
4. 对极端陈旧活动进行 Java 自动恢复，而不仅向模型提供 `STALE` 信号。
