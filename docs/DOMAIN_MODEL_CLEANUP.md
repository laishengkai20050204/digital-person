# 领域模型精简与保留决策

本文记录第四批低风险精简的实际结论，避免后续仅凭“当前没有调用”删除仍属于明确路线图的类型。

## 已删除：独立状态更新入口

以下类型已删除：

- `UpdatePersonStateService`
- `StateUpdateResult`

原因：

1. 当前没有 HTTP、调度器或其他生产应用服务调用这个独立入口。
2. 人物事件命令和自主活动决策都会通过 `PersonStateEvolutionCoordinator` 完成状态结算、待处理效果评估和统一保存。
3. 继续保留独立状态更新入口会形成第三种触发方式，增加重复模型调用、乐观锁冲突和维护成本。
4. 原有测试覆盖的“全部效果评估成功后才保存”语义，已经由事件命令、活动决策和共享协调器测试覆盖。

如果未来需要纯时间驱动、但不进行活动决策的状态结算，应先定义明确用例和调用方，再新增窄接口；不要恢复一个无调用方的通用服务。

## 已调整：人物生命周期入口

`Person` 新增明确工厂：

- `Person.create(...)`：生成新 `PersonId`、基线状态、空时间线和初始状态演化上下文。
- `Person.reconstitute(...)`：从持久化内容恢复稳定 id、身份、状态、时间线和演化上下文。

生产人物创建现在使用 `Person.create(...)`，聚合复制使用 `Person.reconstitute(...)`。

旧构造函数暂时保留，以避免本批次产生大量无业务价值的测试改写。后续只有在全部调用方已经迁移并经过完整 CI 后，才删除兼容构造函数。

## 已调整：HEXACO 人格值对象

`Personality` 改为 Java `record`，仍然保持六个 HEXACO 维度：

1. `honestyHumility`
2. `emotionality`
3. `extraversion`
4. `agreeableness`
5. `conscientiousness`
6. `openness`

所有维度继续要求为 `0.0` 到 `1.0` 的有限值。为保持现有持久化和适配器兼容，仍提供原来的 `getXxx()` 访问器。

不得把该模型误改为 Big Five，也不得加入 `neuroticism` 替代 HEXACO 的诚实—谦逊或情绪性维度。

## 明确保留：对话决策骨架

以下类型目前可能尚未进入完整生产链路，但与下一阶段微信对话、主动消息和回复决策直接对应，因此保留：

- `DecisionMaker`
- `DialogueContext`
- `DialogueDecision`
- `ReplyIntent`
- `ConversationMessageAssembler`

其中 `ConversationMessageAssembler` 已明确承担历史消息角色保持：用户消息继续作为 `user`，人物消息继续作为 `assistant`，系统消息继续作为 `system`。这比把完整历史拼成单个字符串更适合后续长期对话。

这些类型后续可以根据真实微信入口调整字段，但不能仅因当前调用量少而删除。

## 本批次不做

- 不拆 Maven 多模块。当前代码规模和部署方式尚不足以证明模块化收益高于构建复杂度。
- 不为精简而修改数据库 JSON Schema。
- 不改人物 API、调度表或环境变量。
- 不删除未来对话、记忆和主动消息所需的领域端口。

## 后续删除标准

一个生产类型只有同时满足以下条件时才删除：

1. 仓库内无生产调用方；
2. 没有近期路线图用途；
3. 已有其他单一入口完整覆盖其职责；
4. 删除后完整 Maven `verify`、Spring 装配和 MySQL 集成测试通过；
5. 文档中不存在仍承诺该能力的公开接口。
