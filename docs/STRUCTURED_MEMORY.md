# 结构化事实记忆与混合检索

Digital Person 的长期记忆现在分为两个互补来源：

```text
MySQL 结构化记忆
  ├─ canonical entity
  ├─ entity alias
  └─ typed fact

Mem0 语义记忆
  ├─ 对话中提取的原子记忆
  └─ 向量相似度检索

HybridPersonMemoryGateway
  ├─ 并行读取两个来源
  ├─ 按相关度统一排序
  ├─ 按 section + 正文去重
  └─ 应用一次全局数量上限
```

结构化层不使用用户原句做 SQL 等值匹配。自然语言先被转换为白名单查询对象，再使用实体 ID、分区、领域、谓词和有效时间查询数据库。Mem0 继续负责无法预先确定实体和字段的模糊经历检索。

## 数据表

Flyway `V6__create_structured_memory.sql` 创建三张表：

- `memory_entity`：人物、地点、游戏、组织等规范实体；
- `memory_entity_alias`：昵称、简称、错别字来源和别名置信度；
- `person_memory_fact`：分区、领域、主语实体、谓词、宾语实体、文本值、有效期、重要性、置信度和证据次数。

所有数据都使用 `person_id` 隔离。事实引用实体时，Java 写入端会先验证实体属于同一个数字人物。

## 分区

`MemorySection` 当前包括：

```text
IDENTITY
RELATIONSHIP
PREFERENCE
GOAL
PLAN
COMMITMENT
EPISODIC
USER_PROFILE
ROUTINE
SCHEDULE
EMOTIONAL_PATTERN
WORKING_MEMORY
CONVERSATION_SUMMARY
```

一个结构化事实有一个主分区和一个领域代码。例如：

```text
section = PREFERENCE
domain = GAME
predicate = LIKES_MOST
textValue = 马超
statement = 用户主要玩马超
```

## 查询流程

```text
PersonMemoryQuery
  ↓
StructuredMemoryQueryPlanner
  ↓
StructuredMemoryQueryPlan
  ├─ sections
  ├─ domains
  ├─ predicates
  ├─ entityTypes
  └─ entityMention
  ↓
别名与错字实体解析
  ↓
StructuredMemoryQuery
  ↓
参数化 JDBC 查询
```

当前默认实现是 `HeuristicStructuredMemoryQueryPlanner`。它只生成受限字段，不生成 SQL。以后接入 LLM 规划器时，只需要替换 `StructuredMemoryQueryPlanner` Bean；模型仍不能访问表名、列名或提交任意 SQL。

## 错字与别名

`MemoryTextNormalizer` 会统一：

- Unicode 兼容字符；
- 大小写；
- 空格；
- 标点；
- 全角和半角字符。

`MemoryTextSimilarity` 综合使用：

- 完全匹配；
- 包含关系；
- Levenshtein 编辑距离；
- 字符 n-gram Jaccard 相似度。

因此数据库中的 `林晓雨` 可以把 `林小雨` 作为候选实体，`王者` 也可以匹配 `王者荣耀`。候选实体不会直接成为事实答案；系统取得稳定的 `entity_id` 后，才查询对应结构化事实。

## 事实更新

结构化事实使用稳定 `fact_key` 幂等写入。相同主语、谓词、宾语和文本值再次出现时：

- 保留同一个 `fact_id`；
- `evidence_count` 增加；
- 置信度和重要性只会上升；
- `last_confirmed_at` 更新；
- 有效期可以被刷新。

一次聊天中的临时表达不会自动覆盖长期事实。后续的记忆提取器应通过这个端口写入，而不是直接拼接 SQL。

## 混合网关的可用性

- 任一来源成功返回时，整体为 `AVAILABLE`；
- 两个来源都关闭时，整体为 `DISABLED`；
- 没有可用来源且至少一个来源故障时，整体为 `UNAVAILABLE`；
- 单个来源故障不会阻止另一个来源进入模型上下文。

## 配置

结构化记忆依赖 MySQL 持久化：

```bash
MYSQL_PERSISTENCE_ENABLED=true
STRUCTURED_MEMORY_ENABLED=true
```

可调参数：

```bash
STRUCTURED_MEMORY_MINIMUM_ENTITY_SIMILARITY=0.60
STRUCTURED_MEMORY_MAXIMUM_ENTITY_CANDIDATES=300
```

建议先保持 `STRUCTURED_MEMORY_ENABLED=false` 完成数据库迁移和数据写入验证，再开启检索注入。Mem0 可以独立开启或关闭。

## 当前边界

本阶段已经完成：

- 结构化实体、别名和事实数据模型；
- MySQL 迁移与 JDBC 存储；
- 受限查询对象；
- 自然语言分区规划；
- 别名和错字实体解析；
- 结构化与 Mem0 混合检索；
- 失效时间过滤、幂等写入和证据累计。

本阶段没有把所有聊天自动写成结构化事实。自动提取应作为独立模型流程实现，并通过 `StructuredMemoryRepository` 写入，避免把未经确认的一次性表达直接升级为永久资料。
