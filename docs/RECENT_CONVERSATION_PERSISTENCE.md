# 近期对话 MySQL 持久化

本文说明正式对话如何保存原始消息、如何在下一轮加载最近对话，以及生产环境如何验证和排障。

## 1. 执行链路

```text
用户发送消息
  ↓
读取人物聚合
  ↓
并行加载 Mem0 长期记忆和 MySQL 近期原始对话
  ↓
组装人物上下文并调用对话模型
  ↓
模型生成回复
  ↓
并行执行：
  ├─ 用户消息 + 人物回复写入 MySQL
  └─ 本轮对话交给 Mem0 提取长期记忆
  ↓
返回回复和两条持久化链路的状态
```

当前消息不会在模型调用前写入近期对话，因此不会在同一轮上下文中重复出现。只有已经完成的对话交换会进入后续轮次。

## 2. 数据库结构

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

人物被删除时，对应原始对话通过外键自动删除。

对话记录使用独立表，而不是写进 `digital_person.aggregate_json`。因此追加聊天不会修改人物聚合版本，也不会与人物状态更新争夺乐观锁。

## 3. 事务和失败行为

一个成功对话中的用户消息和全部人物回复在同一个 MySQL 事务中追加。

如果模型已经生成回复，但原始对话写入失败：

```text
仍然返回模型回复
conversationStatus = FAILED
persistedConversationTurnCount = 0
```

Mem0 写回与原始对话写入彼此独立：一条链路失败不会伪装成另一条链路失败，也不会吞掉已经生成的用户回复。

## 4. 保留策略

每个人物默认保留最近 500 条原始消息：

```bash
CONVERSATION_RETENTION_TURNS=500
```

这里的“条”是消息条数，不是对话轮数。一轮包含一条用户消息和一条人物回复时，占用两条。

模型上下文默认只加载最近 12 条：

```bash
DIALOGUE_MAX_CONVERSATION_TURNS=12
```

两个参数职责不同：

- `CONVERSATION_RETENTION_TURNS` 控制 MySQL 中实际保留多少原始消息；
- `DIALOGUE_MAX_CONVERSATION_TURNS` 控制一次模型调用最多注入多少条近期消息。

## 5. 正式接口响应

成功响应新增：

```json
{
  "conversationStatus": "STORED",
  "persistedConversationTurnCount": 2,
  "memoryStatus": "PROCESSED",
  "memoryMutationCount": 0
}
```

`conversationStatus`：

| 状态 | 含义 |
|---|---|
| `STORED` | 本轮用户消息和人物回复已写入 MySQL |
| `DISABLED` | 没有启用 MySQL 对话存储 |
| `FAILED` | 回复已生成，但本轮原始对话写入失败 |

`persistedConversationTurnCount` 是本轮成功写入的消息条数。单回复场景通常为 `2`。

`memoryStatus` 仍只表示 Mem0 的对话后长期记忆处理结果，与 MySQL 原始对话状态无关。

## 6. 部署后验证

确认运行版本和服务：

```bash
sudo readlink -f /opt/person-ai/current.jar
systemctl is-active person-ai
curl -fsS http://127.0.0.1:8080/actuator/health | jq
```

确认 Flyway 已创建表：

```bash
sudo mysql digital_person -e \
  "SHOW TABLES LIKE 'person_conversation_turn';"
```

调用一次正式对话后检查最近记录：

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
LIMIT 12;
"
```

运维检查默认只显示正文长度，不应在共享终端、截图或日志中直接输出私人聊天正文。

## 7. 验证连续对话

第一轮发送：

```text
我今天准备晚上复习线性代数。
```

第二轮发送：

```text
我刚才说晚上要做什么？
```

第二轮模型上下文会包含第一轮保存的 USER 和 PERSON 消息。即使这件事尚未被 Mem0 判断为长期记忆，近期对话仍能维持连续性。

## 8. 日志与排障

查看对话相关日志：

```bash
sudo journalctl \
  -u person-ai \
  --since "10 minutes ago" \
  --no-pager \
  -o cat \
| grep -E \
  'Dialogue memory retrieval completed|Dialogue conversation persistence failed'
```

原始对话读取失败时，系统会记录警告并在没有近期对话的情况下继续生成回复。写入失败时，接口返回 `conversationStatus=FAILED`。

日志不得包含原始消息正文，只记录人物 ID、条数、状态和异常类型。

## 9. 当前边界

当前实现已经支持：

- MySQL 原始对话持久化；
- 应用重启后继续读取；
- 最近 N 条按原始顺序注入上下文；
- 每人物独立保留上限；
- 人物删除时级联删除；
- 写入失败不吞掉模型回复；
- 与 Mem0 长期记忆并行工作。

尚未实现：

- 按会话线程或渠道区分微信、网页和其他入口；
- 消息编辑、撤回和软删除；
- 对原始聊天正文做应用层加密；
- 面向用户的对话历史查询和清除 API；
- 将过旧原始对话自动摘要后再删除。
