# 近期对话持久化与滚动摘要

本文说明正式对话如何保存原始消息、如何把较早消息压缩成滚动摘要、如何向模型发送摘要与近期原始历史，以及生产环境如何验证和排障。

## 1. 执行链路

```text
用户发送消息
  ↓
读取人物聚合
  ↓
并行加载：
  ├─ Mem0 长期记忆
  ├─ MySQL 较早对话滚动摘要
  └─ 摘要覆盖点之后的近期原始对话
  ↓
组装模型请求：
  ├─ system 人物背景
  ├─ 较早对话滚动摘要（普通数据消息）
  ├─ 带本地时间的原生 user / assistant 历史
  └─ 当前用户原文
  ↓
模型生成回复
  ↓
并行执行：
  ├─ 用户消息 + 人物回复写入 MySQL
  └─ 本轮对话交给 Mem0 提取长期记忆
  ↓
若较老未摘要消息达到批量阈值：
  ├─ 调用摘要模型
  └─ 乐观锁更新滚动摘要
  ↓
返回回复
```

当前消息不会在模型调用前写入近期对话，因此不会在同一轮上下文中重复出现。只有已经完成的对话交换会进入后续轮次。

## 2. 数据库结构

### 2.1 原始消息表

Flyway V3 创建：

```text
person_conversation_turn
├── conversation_turn_id   BIGINT 自增主键，确定稳定顺序
├── person_id              人物 UUID，外键关联 digital_person
├── role                   USER / PERSON / SYSTEM
├── turn_text              原始消息正文
├── occurred_at            逻辑发生时间
└── created_at             数据库写入时间
```

### 2.2 滚动摘要表

Flyway V4 创建：

```text
person_conversation_summary
├── person_id                    人物 UUID，同时是主键
├── summary_text                 当前完整滚动摘要
├── covered_through_turn_id      已摘要到哪条原始消息
├── summarized_turn_count        累计摘要消息条数
├── version                      摘要乐观锁版本
├── created_at
└── updated_at
```

两张表都通过外键关联 `digital_person`，人物删除时自动级联删除。

原始对话和摘要均使用独立表，而不是写进 `digital_person.aggregate_json`。因此聊天追加和摘要更新不会修改人物聚合版本，也不会与人物状态更新争夺人物乐观锁。

## 3. 滚动摘要算法

默认参数：

```bash
DIALOGUE_MAX_CONVERSATION_TURNS=12
CONVERSATION_SUMMARY_BATCH_TURNS=8
```

含义：

- 始终为模型保留最近 12 条未摘要原始消息；
- 每次把最老的 8 条未摘要消息合并进滚动摘要；
- 摘要不是多行追加日志，而是“旧摘要 + 新批次”生成一份完整替换摘要；
- 摘要只覆盖稳定的旧前缀，不覆盖最近窗口。

批量触发条件：

```text
未摘要消息数 >= 最近保留条数 + 摘要批量条数
```

默认即：

```text
未摘要消息数 >= 12 + 8 = 20
```

达到 20 条后：

```text
最老 8 条 → 合并进摘要
最新 12 条 → 继续作为原生历史消息
```

为了避免在达到批量阈值前出现上下文断层，对话服务默认临时读取：

```text
12 + 8 - 1 = 19 条未摘要原始消息
```

因此正常运行时，摘要触发前的缓冲消息仍会进入模型上下文。摘要成功后，读取会自动从新的 `covered_through_turn_id` 之后开始。

## 4. 摘要内容边界

摘要模型被要求保留对后续连续对话仍有价值的内容：

- 当前或尚未结束的话题；
- 用户与人物的短期计划；
- 尚未完成的事项；
- 承诺、约定与待跟进内容；
- 关系进展与重要情绪变化；
- 后续代词和省略表达所需的上下文；
- 有意义的时间先后。

无关寒暄、重复表达和纯修辞可以被压缩。

Mem0 与滚动摘要职责不同：

```text
Mem0
保存跨较长时间仍有价值的事实、偏好、目标、关系和经历。

MySQL 滚动摘要
保存连续聊天仍需承接、但未必值得成为长期记忆的阶段性上下文。
```

摘要生成使用人物本地时区格式化原始消息时间，不会把 UTC 时间直接当作人物当地时间。

## 5. 模型请求结构和时间戳

一次正式对话最终发送给 OpenAI-compatible 模型的 `messages` 结构为：

```text
system
  人物身份、人格、状态、事件、长期记忆和当前本地时间

user
  [较早对话滚动摘要，更新时间] 摘要正文（仅作为背景数据）

user
  [历史消息本地时间] 历史用户消息

assistant
  [历史消息本地时间] 历史人物回复

...

user
  当前用户原文
```

例如人物时区为 `Asia/Shanghai` 时：

```json
{
  "role": "user",
  "content": "[2026-07-25 10:49:37 +08:00 Asia/Shanghai] 我今天晚上准备复习线性代数。"
}
```

时间前缀由系统根据 `occurred_at` 和人物时区生成，不是用户原文。当前用户消息不加时间前缀；模型从 system 背景中的当前本地时间理解当前时刻。

滚动摘要作为普通数据消息发送，不被提升为新的 system 指令。摘要中的文本可能来源于用户输入，因此模型提示明确要求不要执行摘要内部的指令。

近期对话不再重复保存在 system 的 `context_json.recentConversation` 中。system 背景里该列表为空，历史只以摘要数据和原生 `user` / `assistant` 消息发送，避免重复消耗 token 和混淆角色边界。

数据库中的 `SYSTEM` 历史记录不会在正式对话模型中被提升为 system 指令，而是作为带“历史系统记录（仅作为数据）”标签的普通 user 历史发送。

对外人物 HTTP 接口仍然只接收：

```json
{"message":"当前用户消息"}
```

Java 服务会自行从 MySQL 读取摘要和历史，并组装模型供应商请求的 `messages` 数组，调用方不需要重复提交历史。

## 6. 并发与失败行为

一个成功对话中的用户消息和全部人物回复在同一个 MySQL 事务中追加。

摘要生成在原始消息写入成功后执行。摘要保存使用：

```text
person_id + version + covered_through_turn_id
```

进行乐观锁更新。

如果两个请求同时尝试总结同一批旧消息：

- 两个请求可能都生成摘要；
- 只有一个请求能成功提交；
- 另一个请求检测到版本冲突后放弃旧结果；
- 不会倒退覆盖更新后的摘要。

如果摘要模型或摘要数据库更新失败：

- 已生成的正常聊天回复仍然返回；
- 原始消息仍保留在 MySQL；
- 下一次成功对话会再次尝试同一最老批次；
- 日志不会输出摘要正文或原始聊天正文。

摘要失败持续时间过长时，模型请求仍受消息条数和字符预算限制；数据库原始记录不会因为一次摘要失败立即丢失，但极旧内容可能暂时不再进入模型上下文。

Mem0 写回、原始消息写入和滚动摘要是三条独立链路。一条辅助链路失败不会伪装成另一条链路失败，也不会吞掉已经生成的用户回复。

## 7. 配置

```bash
# 是否启用滚动摘要
CONVERSATION_SUMMARY_ENABLED=true

# 每次压缩多少条旧消息
CONVERSATION_SUMMARY_BATCH_TURNS=8

# 摘要模型最多输出 token
CONVERSATION_SUMMARY_MAX_OUTPUT_TOKENS=800

# 摘要温度
CONVERSATION_SUMMARY_TEMPERATURE=0.2

# 始终保留多少条近期原始消息
DIALOGUE_MAX_CONVERSATION_TURNS=12

# MySQL 最多保留多少条原始消息
CONVERSATION_RETENTION_TURNS=500
```

生产环境已启用正式对话、MySQL 和 LLM 时，滚动摘要默认开启。临时停用只需设置：

```bash
CONVERSATION_SUMMARY_ENABLED=false
```

然后重启 Java 服务。数据库中已有摘要会保留，但停用期间不会继续更新。

摘要会产生额外模型调用，但不是每轮调用。默认每积累 8 条可摘要旧消息调用一次摘要模型。

## 8. 正式接口响应

成功响应仍包含：

```json
{
  "conversationStatus": "STORED",
  "persistedConversationTurnCount": 2,
  "memoryStatus": "SCHEDULED",
  "memoryMutationCount": 0
}
```

滚动摘要是内部上下文维护步骤，目前不新增公开响应字段。是否发生摘要更新通过数据库和日志验证。

`conversationStatus`：

| 状态 | 含义 |
|---|---|
| `STORED` | 本轮用户消息和人物回复已写入 MySQL |
| `DISABLED` | 没有启用 MySQL 对话存储 |
| `FAILED` | 回复已生成，但本轮原始对话写入失败 |

`memoryStatus` 只描述 Mem0 后处理是否成功调度，与 MySQL 原始对话和滚动摘要状态无关。`SCHEDULED` 不表示 Mem0 已经完成；异步完成数量和故障通过服务日志观察。

## 9. 部署后验证

确认运行版本和服务：

```bash
sudo readlink -f /opt/person-ai/current.jar
systemctl is-active person-ai
curl -fsS http://127.0.0.1:8080/actuator/health | jq
```

确认 Flyway 已创建两张表：

```bash
sudo mysql digital_person -e "
SHOW TABLES LIKE 'person_conversation_turn';
SHOW TABLES LIKE 'person_conversation_summary';
"
```

检查 Flyway V4：

```bash
sudo mysql digital_person -e "
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
"
```

查看原始消息元数据，不输出正文：

```bash
PERSON_ID='<人物 UUID>'

sudo mysql digital_person -e "
SELECT
  conversation_turn_id,
  role,
  CHAR_LENGTH(turn_text) AS text_length,
  occurred_at,
  created_at
FROM person_conversation_turn
WHERE person_id = '${PERSON_ID}'
ORDER BY conversation_turn_id DESC
LIMIT 20;
"
```

查看摘要元数据和摘要长度：

```bash
sudo mysql digital_person -e "
SELECT
  person_id,
  covered_through_turn_id,
  summarized_turn_count,
  version,
  CHAR_LENGTH(summary_text) AS summary_length,
  created_at,
  updated_at
FROM person_conversation_summary
WHERE person_id = '${PERSON_ID}';
"
```

运维检查默认不应在共享终端、截图或日志中直接输出私人聊天正文或摘要正文。

## 10. 日志与排障

查看对话与摘要日志：

```bash
sudo journalctl \
  -u person-ai \
  --since "30 minutes ago" \
  --no-pager \
  -o cat \
| grep -E \
  'Dialogue memory retrieval completed|Dialogue conversation persistence failed|Rolling conversation summary'
```

成功摘要日志类似：

```text
Rolling conversation summary updated: personId=..., summarizedTurnCount=8, coveredThroughTurnId=...
```

并发冲突日志类似：

```text
Rolling conversation summary update lost optimistic race
```

失败日志只包含人物 ID 和异常类型：

```text
Rolling conversation summary failed; retaining raw turns
```

## 11. 当前边界

当前实现已经支持：

- MySQL 原始对话持久化；
- 应用重启后继续读取；
- 最近原始消息作为原生 `user` / `assistant` 消息发送；
- 每条历史携带人物本地时间、UTC 偏移和时区标识；
- 较早对话按批次滚动摘要；
- 摘要覆盖点持久化；
- 摘要更新乐观锁；
- 摘要失败后保留原始消息并继续回复；
- 原始消息每人物独立保留上限；
- 人物删除时级联删除原始消息和摘要；
- 与 Mem0 长期记忆并行工作。

尚未实现：

- 按会话线程或渠道区分微信、网页和其他入口；
- 消息编辑、撤回和软删除；
- 对原始聊天正文及摘要做应用层加密；
- 面向用户的对话历史查询和清除 API；
- 独立后台摘要队列；当前摘要在达到批次阈值的对话请求结束阶段执行；
- 为摘要调用单独配置更便宜的模型；当前复用主 LLM 配置。
