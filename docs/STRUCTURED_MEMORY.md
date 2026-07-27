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

Flyway `V6__create_structured_memory.sql` 创建三张核心表：

- `memory_entity`：人物、地点、游戏、组织等规范实体；
- `memory_entity_alias`：昵称、简称、错别字来源和别名置信度；
- `person_memory_fact`：分区、领域、主语实体、谓词、宾语实体、文本值、有效期、重要性、置信度和证据次数。

Flyway `V7__add_structured_memory_extraction.sql` 增加：

- `person_structured_memory_extraction_cursor`：每个人物已经处理到的原始对话行、乐观锁版本和最近提取数量；
- `person_memory_fact_evidence`：事实与原始对话批次的证据关联，同一来源范围重试不会重复增加 `evidence_count`。

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

自动提取使用来源范围去重。同一批原始对话即使因并发或游标竞争重试，也不会重复增加证据次数。只有高置信度且属于 `IDENTITY`、`USER_PROFILE`、`SCHEDULE` 或 `WORKING_MEMORY` 的单值更新，才允许把同一事实槽位中的旧事实设置为失效；关系、偏好、目标和计划默认并存。

## 自动提取流程

```text
完成的用户消息与人物回复写入 MySQL
  ↓
异步后处理检查独立提取游标
  ↓
保留最近消息，取得最旧稳定批次
  ↓
LLM 仅输出白名单 JSON 候选
  ├─ entities：规范名、类型、别名、置信度
  └─ facts：section、domain、predicate、有效期和冲突模式
  ↓
Java 过滤测试内容、凭据、低置信度和低重要性
  ↓
高阈值实体解析或幂等创建
  ↓
来源范围幂等事实写入与保守冲突失效
  ↓
乐观锁推进提取游标
```

提取在人物回复返回后运行。模型、数据库或游标失败不会撤回已经生成的回复；失败批次不会推进游标，会在后续对话后重试。模型不能生成 SQL，也不能直接指定数据库 ID。

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

STRUCTURED_MEMORY_EXTRACTION_ENABLED=false
STRUCTURED_MEMORY_EXTRACTION_RECENT_TURNS_TO_KEEP=2
STRUCTURED_MEMORY_EXTRACTION_BATCH_TURNS=8
STRUCTURED_MEMORY_EXTRACTION_MAXIMUM_ENTITIES=8
STRUCTURED_MEMORY_EXTRACTION_MAXIMUM_FACTS=12
STRUCTURED_MEMORY_EXTRACTION_MINIMUM_CONFIDENCE=0.70
STRUCTURED_MEMORY_EXTRACTION_MINIMUM_IMPORTANCE=0.35
STRUCTURED_MEMORY_EXTRACTION_MAX_OUTPUT_TOKENS=1400
STRUCTURED_MEMORY_EXTRACTION_TEMPERATURE=0.1
```

建议先完成数据库迁移和受保护接口验证，再保持 `STRUCTURED_MEMORY_ENABLED=true`、关闭测试接口。自动提取默认关闭，应在混合检索稳定后单独开启。Mem0 可以独立开启或关闭。

## 当前边界

当前已经完成：

- 结构化实体、别名和事实数据模型；
- MySQL 迁移与 JDBC 存储；
- 受限查询对象；
- 自然语言分区规划；
- 别名和错字实体解析；
- 结构化与 Mem0 混合检索；
- 稳定对话批次自动提取；
- 提取游标、来源证据去重和保守冲突失效；
- 失效时间过滤、幂等写入和证据累计。

自动提取仍然是模型判断，不等同于用户确认。涉及高风险资料、法律身份、财务账户、认证凭据或需要强一致性的业务事实，不应仅凭自动提取结果执行外部操作。
