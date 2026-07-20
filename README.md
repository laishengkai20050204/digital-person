# Digital Person

一个用于探索“数字人物”建模的 Java 原型。

当前版本保持最小设计：`Person` 只包含两个核心属性：

- `personality`：人物性格
- `lifeEvents`：人物经历过的事件列表

## 项目结构

```text
src/main/java/com/laishengkai/digitalperson/
├── Person.java
├── Personality.java
└── LifeEvent.java
```

后续可以逐步加入记忆、情绪、关系状态和行为决策，但当前阶段不提前构建复杂系统。
