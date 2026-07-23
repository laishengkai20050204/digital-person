# Spring 装配、人物调度创建与诊断边界

本文记录核心人物服务、持久化调度记录和内部诊断接口的生产装配规则。

## 1. Spring Bean 所有权

核心人物应用服务由 `PersonApplicationConfiguration` 统一拥有：

- `Clock`
- `StateUpdater`
- `StateEvaluationContextAssembler`
- `PersonDirectoryService`
- `PersonEventCommandService`
- `UpdatePersonStateService`

`PersonApiConfiguration` 只负责绑定 `PersonApiProperties`，不再创建应用服务。人物 API 开启时，目录和事件服务按配置属性确定性注册；缺少必需的仓储或状态评估依赖时，应用启动明确失败，而不是静默缺少路由。

活动决策模型和 `PersonActivityDecisionService` 仍由活动功能配置拥有，因为它们同时服务于人工 API 和持久化调度器。

## 2. 人物创建与首轮调度

MySQL 持久化启用时，`ScheduledPersonCreationRepository` 是首选 `PersonCreationRepository`。一次创建操作在同一个数据库事务中执行：

```text
INSERT digital_person
        ↓
INSERT IGNORE person_activity_schedule
        ↓
统一提交或统一回滚
```

首轮 `next_review_at` 为：

```text
创建时间 + ACTIVITY_SCHEDULER_INITIAL_REVIEW_DELAY
```

全局调度器即使暂时关闭，调度行仍会创建。之后开启调度器时，已有计划可以直接恢复。

## 3. 到期轮询与低频补漏

高频 poll 只查询 `person_activity_schedule` 中已经到期的记录，不再扫描完整 `digital_person` 表。

完整补漏由独立 reconciliation 执行：

```text
ACTIVITY_SCHEDULER_RECONCILIATION_INITIAL_DELAY=5s
ACTIVITY_SCHEDULER_RECONCILIATION_INTERVAL=1h
```

补漏使用幂等 `INSERT IGNORE ... SELECT`，用于修复历史数据、人工 SQL 插入或异常迁移遗留的缺失调度行。多实例同时执行也是安全的。

## 4. 生产诊断开关

连接冒烟测试和原始诊断已拆分：

```text
LLM_CONNECTION_TEST_ENABLED
LLM_CONNECTION_TEST_TOKEN
```

只控制固定提示词的连接测试和 Agent 工具循环测试。

以下接口会暴露完整 system prompt、序列化上下文、工具 Schema、原始工具参数和 token 用量：

```text
/internal/state/evaluation-diagnostics/**
/internal/state/evaluation-contrasts/**
```

它们只有同时满足以下条件才会注册：

```text
LLM_ENABLED=true
DIAGNOSTICS_ENABLED=true
DIAGNOSTICS_TOKEN=<独立高强度令牌>
```

生产环境默认：

```text
DIAGNOSTICS_ENABLED=false
```

除临时排障外不要开启。排障完成后应关闭并重启服务。

## 5. 生产建议配置

```bash
ACTIVITY_SCHEDULER_RECONCILIATION_INITIAL_DELAY=5s
ACTIVITY_SCHEDULER_RECONCILIATION_INTERVAL=1h

DIAGNOSTICS_ENABLED=false
# 只有临时启用诊断时才设置：
# DIAGNOSTICS_TOKEN=<随机长令牌>
```

检查补漏日志：

```bash
sudo journalctl -u person-ai -b --no-pager -o cat \
  | grep -E 'Reconciled missing persistent activity schedules|schedule reconciliation failed'
```

确认原始诊断接口默认未注册：

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  http://127.0.0.1:8080/internal/state/evaluation-diagnostics/scenarios
```

默认应返回 `404`。
