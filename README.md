# Digital Person

一个以领域模型为核心的 Java 21 数字人物原型。当前重点是把人物的性格、事件时间线、短期状态和异步状态评估建立成可持久化、可测试、可替换基础设施的内核。

## 当前能力

- HEXACO 稳定人格模型
- 人物与用户的独立事件时间线
- 按活动渠道处理并发活动
- 情绪、认知、身体和社交短期状态
- 指数状态变化模型
- 可复用、无状态的 `StateUpdater`
- 异步 `StateTransitionEvaluator` 边界，方便后续接入 LangChain4j
- `PersonId`、`PersonRepository` 和应用层状态更新服务
- 防御性复制，避免调用方绕过时间线和状态规则
- 基于 SLF4J 与 Logback 的结构化运行日志

## 架构

```text
src/main/java/com/laishengkai/digitalperson/
├── application/
│   ├── UpdatePersonStateService.java
│   ├── StateUpdateResult.java
│   └── PersonNotFoundException.java
├── dialogue/
├── experience/
│   ├── EventTimeline.java
│   ├── PersonEvent.java
│   └── TimeRange.java
├── person/
│   ├── Person.java
│   ├── PersonId.java
│   └── PersonRepository.java
├── personality/
└── state/
    ├── StateUpdater.java
    ├── StateEvolutionContext.java
    ├── StateUpdatePreparation.java
    └── StateTransitionEvaluator.java
```

领域层不依赖 LangChain4j、Mem0、数据库或微信。后续实现应通过接口放在基础设施层。

## 状态更新流程

```text
读取 Person
  ↓
复制当前 PersonState
  ↓
StateUpdater.prepare
  ├─ 结算旧活动效果
  └─ 找出需要重新评估的新活动
  ↓
异步 StateTransitionEvaluator
  ↓
StateUpdater.complete
  ↓
Person.commitStateUpdate
  ↓
PersonRepository.save
```

`StateUpdater` 不保存任何人物专属状态。`lastUpdatedAt` 和每个活动渠道的效果保存在不可变的 `StateEvolutionContext` 中，并与人物一起持久化。

如果 LLM 评估失败，工作副本不会提交到 `Person`，也不会调用 Repository 保存。

## 日志

项目代码通过 SLF4J API 写日志，当前运行时实现为 Logback。默认输出到标准输出，方便 Docker、systemd 或云平台统一采集；项目本身不直接管理日志文件。

默认日志级别为 `INFO`，可以使用环境变量调整：

```bash
export LOG_LEVEL=INFO
export STATE_LOG_LEVEL=DEBUG
export EVENT_LOG_LEVEL=DEBUG
```

日志覆盖以下关键流程：

- 人物状态更新的开始、完成、失败和耗时
- 待评估活动渠道及状态结算过程
- 事件开始、替换、结束、记录和删除

日志只记录 `PersonId`、`EventId`、活动类型、渠道、时间和数量等结构化信息。禁止记录聊天正文、事件标题、地点、参与者、备注、提示词和长期记忆内容，避免泄露隐私数据。

测试使用独立的 `logback-test.xml`，默认只输出 `WARN` 和 `ERROR`。

## 注释规范

- 对聚合根、应用服务、不可变值对象和重要领域服务使用类级 Javadoc。
- 对存在事务边界、异步边界或不明显副作用的方法说明前置条件与失败语义。
- 不为显而易见的 getter 或简单赋值逐行添加注释。
- 注释解释“为什么”和业务约束，不重复代码已经清楚表达的“做什么”。

## 构建

```bash
mvn verify
```

GitHub Actions 会使用 Java 21 执行编译、单元测试和 JaCoCo 报告生成。

## 下一步

1. 实现数据库版 `PersonRepository`
2. 使用 LangChain4j 实现 `StateTransitionEvaluator`
3. 接入 Mem0 长期记忆
4. 增加聊天应用服务和消息渠道适配器
5. 增加乐观锁，防止同一人物的并发更新覆盖
