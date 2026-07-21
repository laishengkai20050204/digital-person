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
